/*
 * libqosintl: translations for qos Desktop.
 *
 * Termux builds glib without a gettext library (its libintl is a stub), so
 * XFCE's programs show English whatever the language. This library is loaded
 * with LD_PRELOAD by qos-xsession and supplies glib's translation functions
 * itself: it reads the .mo catalogue for the language in LANGUAGE (or LANG)
 * from $PREFIX/share/locale/<lang>/LC_MESSAGES/<domain>.mo and returns the
 * translation, or the English text when there is none.
 */
#include <fcntl.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#ifndef QOS_LOCALE_DIR
#define QOS_LOCALE_DIR "/data/data/com.termux/files/usr/share/locale"
#endif

#define MAX_CATALOGUES 64

struct catalogue {
    char domain[64];
    const unsigned char *data; /* NULL when the domain has no catalogue */
    size_t size;
    uint32_t count, originals, translations;
};

static struct catalogue catalogues[MAX_CATALOGUES];
static int catalogue_count;
static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static char language[16];
static int language_known;

static uint32_t word(const struct catalogue *c, uint32_t offset) {
    uint32_t v;
    memcpy(&v, c->data + offset, 4);
    return v;
}

/* The first language in LANGUAGE, or LANG's, without territory or codeset; "" for English. */
static const char *current_language(void) {
    if (!language_known) {
        const char *value = getenv("LANGUAGE");
        if (value == NULL || *value == '\0') value = getenv("LANG");
        if (value == NULL) value = "";
        size_t n = strcspn(value, ":_.@");
        if (n >= sizeof language) n = 0;
        memcpy(language, value, n);
        language[n] = '\0';
        if (strcmp(language, "en") == 0 || strcmp(language, "C") == 0 || strcmp(language, "POSIX") == 0)
            language[0] = '\0';
        language_known = 1;
    }
    return language;
}

static const struct catalogue *find_catalogue(const char *domain) {
    const char *lang = current_language();
    /* No domain means the program's own, as textdomain() set it; XFCE's programs
     * name theirs after themselves (thunar, xfce4-terminal and the rest). */
    if (domain == NULL) domain = getprogname();
    if (domain == NULL || *lang == '\0' || strlen(domain) >= sizeof catalogues[0].domain) return NULL;

    pthread_mutex_lock(&lock);
    struct catalogue *found = NULL;
    for (int i = 0; i < catalogue_count; i++) {
        if (strcmp(catalogues[i].domain, domain) == 0) { found = &catalogues[i]; break; }
    }
    if (found == NULL && catalogue_count < MAX_CATALOGUES) {
        found = &catalogues[catalogue_count++];
        strcpy(found->domain, domain);
        char path[512];
        if (snprintf(path, sizeof path, "%s/%s/LC_MESSAGES/%s.mo", QOS_LOCALE_DIR, lang, domain) < (int) sizeof path) {
            int fd = open(path, O_RDONLY | O_CLOEXEC);
            struct stat st;
            if (fd >= 0 && fstat(fd, &st) == 0 && st.st_size >= 28) {
                void *map = mmap(NULL, (size_t) st.st_size, PROT_READ, MAP_PRIVATE, fd, 0);
                if (map != MAP_FAILED) {
                    found->data = map;
                    found->size = (size_t) st.st_size;
                    /* Little-endian .mo files only, as msgfmt writes them on these machines. */
                    if (word(found, 0) == 0x950412deu) {
                        found->count = word(found, 8);
                        found->originals = word(found, 12);
                        found->translations = word(found, 16);
                    } else {
                        found->data = NULL;
                    }
                }
            }
            if (fd >= 0) close(fd);
        }
    }
    pthread_mutex_unlock(&lock);
    return found != NULL && found->data != NULL ? found : NULL;
}

/* The translation of key (a msgid, or "context\004msgid"), with its length; NULL if none. */
static const char *lookup(const char *domain, const char *key, size_t *length) {
    const struct catalogue *c = find_catalogue(domain);
    if (c == NULL || key == NULL) return NULL;
    uint32_t lo = 0, hi = c->count;
    while (lo < hi) { /* msgfmt sorts the originals, so a binary search finds the key */
        uint32_t mid = lo + (hi - lo) / 2;
        uint32_t len = word(c, c->originals + mid * 8), off = word(c, c->originals + mid * 8 + 4);
        if ((size_t) off + len >= c->size) return NULL;
        int cmp = strcmp(key, (const char *) c->data + off);
        if (cmp == 0) {
            uint32_t tlen = word(c, c->translations + mid * 8), toff = word(c, c->translations + mid * 8 + 4);
            if (tlen == 0 || (size_t) toff + tlen >= c->size) return NULL;
            if (length != NULL) *length = tlen;
            return (const char *) c->data + toff;
        }
        if (cmp < 0) hi = mid; else lo = mid + 1;
    }
    return NULL;
}

const char *g_dgettext(const char *domain, const char *msgid) {
    const char *t = lookup(domain, msgid, NULL);
    return t != NULL ? t : msgid;
}

const char *g_dcgettext(const char *domain, const char *msgid, int category) {
    (void) category;
    return g_dgettext(domain, msgid);
}

/* Plural forms: the .mo stores "one\0other"; two forms, one for 1, as in Tamil and English. */
const char *g_dngettext(const char *domain, const char *msgid, const char *msgid_plural, unsigned long n) {
    size_t length;
    const char *t = lookup(domain, msgid, &length);
    if (t == NULL) return n == 1 ? msgid : msgid_plural;
    if (n == 1) return t;
    size_t first = strlen(t);
    return first < length ? t + first + 1 : t;
}

const char *g_dpgettext2(const char *domain, const char *context, const char *msgid) {
    size_t c = strlen(context), m = strlen(msgid);
    char stack[512], *key = c + m + 2 <= sizeof stack ? stack : malloc(c + m + 2);
    if (key == NULL) return msgid;
    memcpy(key, context, c);
    key[c] = '\004';
    memcpy(key + c + 1, msgid, m + 1);
    const char *t = lookup(domain, key, NULL);
    if (key != stack) free(key);
    return t != NULL ? t : msgid;
}

/* glib's "context|msgid" or "context\004msgid" form; msgidoffset is where msgid starts, or 0. */
const char *g_dpgettext(const char *domain, const char *msgctxtid, size_t msgidoffset) {
    if (msgidoffset > 0) {
        const char *t = lookup(domain, msgctxtid, NULL);
        return t != NULL ? t : msgctxtid + msgidoffset;
    }
    const char *bar = strchr(msgctxtid, '|');
    if (bar == NULL) return g_dgettext(domain, msgctxtid);
    size_t c = (size_t) (bar - msgctxtid), m = strlen(bar + 1);
    char stack[512], *key = c + m + 2 <= sizeof stack ? stack : malloc(c + m + 2);
    if (key == NULL) return bar + 1;
    memcpy(key, msgctxtid, c);
    key[c] = '\004';
    memcpy(key + c + 1, bar + 1, m + 1);
    const char *t = lookup(domain, key, NULL);
    if (key != stack) free(key);
    return t != NULL ? t : bar + 1;
}

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
#include <ctype.h>
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

/* The catalogue for domain, loaded on first use; NULL if there is none. */
static const struct catalogue *load_catalogue(const char *domain) {
    const char *lang = current_language();
    if (*lang == '\0' || strlen(domain) >= sizeof catalogues[0].domain) return NULL;

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

/* The program's own catalogue, for text asked for without a domain (GtkBuilder's,
 * and libxfce4ui's menus): XFCE's programs set textdomain() to their package,
 * which is usually the program's name. Tried in turn: the name in lower case
 * (Android gives the path as started, and Thunar's daemon runs as "Thunar"), the
 * About window's package, the name without its trailing "-words"
 * (xfwm4-tweaks-settings is xfwm4's), and xfce4-settings for its dialogs. */
static const struct catalogue *program_catalogue(void) {
    static const struct catalogue *cached;
    static int resolved;
    if (resolved) return cached;

    char name[64];
    const char *progname = getprogname();
    if (progname == NULL) progname = "";
    const char *slash = strrchr(progname, '/');
    if (slash != NULL) progname = slash + 1;
    size_t n = 0;
    for (; progname[n] != '\0' && n + 1 < sizeof name; n++)
        name[n] = (char) tolower((unsigned char) progname[n]);
    name[n] = '\0';

    const struct catalogue *c = load_catalogue(name);
    if (c == NULL && strcmp(name, "xfce4-about") == 0) c = load_catalogue("libxfce4ui");
    int settings = n > 9 && strcmp(name + n - 9, "-settings") == 0;
    for (char *dash; c == NULL && (dash = strrchr(name, '-')) != NULL;) {
        *dash = '\0';
        c = load_catalogue(name);
    }
    if (c == NULL && settings) c = load_catalogue("xfce4-settings");
    cached = c;
    resolved = 1;
    return c;
}

static const struct catalogue *find_catalogue(const char *domain) {
    return domain != NULL ? load_catalogue(domain) : program_catalogue();
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

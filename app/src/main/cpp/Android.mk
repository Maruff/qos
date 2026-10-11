LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := libtermux-bootstrap
LOCAL_SRC_FILES := termux-bootstrap-zip.S termux-bootstrap.c
include $(BUILD_SHARED_LIBRARY)

# qos: translations for qos Desktop's programs, preloaded by qos-xsession (see qos-intl/qosintl.c).
include $(CLEAR_VARS)
LOCAL_MODULE := libqosintl
LOCAL_SRC_FILES := qos-intl/qosintl.c
include $(BUILD_SHARED_LIBRARY)

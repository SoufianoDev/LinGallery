#ifndef LIN_LOGGER_H
#define LIN_LOGGER_H

#include <jni.h>

#ifdef __cplusplus
extern "C" {
#endif

#define LIN_LOG_TRACE 0
#define LIN_LOG_DEBUG 1
#define LIN_LOG_INFO  2
#define LIN_LOG_WARN  3
#define LIN_LOG_ERROR 4

void lin_logger_log(JNIEnv *env, int level, const char *tag, const char *msg);
void lin_logger_set_min_level(int level);

#define LIN_LOGT(env, tag, fmt, ...) do {                                 \
    if (env) {                                                             \
        char _lin_buf_[4096];                                              \
        snprintf(_lin_buf_, sizeof(_lin_buf_), fmt, ##__VA_ARGS__);        \
        lin_logger_log(env, LIN_LOG_TRACE, tag, _lin_buf_);                \
    } else {                                                               \
        fprintf(stderr, "TRACE [%s] " fmt "\n", tag, ##__VA_ARGS__);     \
    }                                                                      \
} while(0)

#define LIN_LOGD(env, tag, fmt, ...) do {                                 \
    if (env) {                                                             \
        char _lin_buf_[4096];                                              \
        snprintf(_lin_buf_, sizeof(_lin_buf_), fmt, ##__VA_ARGS__);        \
        lin_logger_log(env, LIN_LOG_DEBUG, tag, _lin_buf_);                \
    } else {                                                               \
        fprintf(stderr, "DEBUG [%s] " fmt "\n", tag, ##__VA_ARGS__);      \
    }                                                                      \
} while(0)

#define LIN_LOGI(env, tag, fmt, ...) do {                                 \
    if (env) {                                                             \
        char _lin_buf_[4096];                                              \
        snprintf(_lin_buf_, sizeof(_lin_buf_), fmt, ##__VA_ARGS__);        \
        lin_logger_log(env, LIN_LOG_INFO, tag, _lin_buf_);                 \
    } else {                                                               \
        fprintf(stderr, "INFO  [%s] " fmt "\n", tag, ##__VA_ARGS__);      \
    }                                                                      \
} while(0)

#define LIN_LOGW(env, tag, fmt, ...) do {                                 \
    if (env) {                                                             \
        char _lin_buf_[4096];                                              \
        snprintf(_lin_buf_, sizeof(_lin_buf_), fmt, ##__VA_ARGS__);        \
        lin_logger_log(env, LIN_LOG_WARN, tag, _lin_buf_);                 \
    } else {                                                               \
        fprintf(stderr, "WARN  [%s] " fmt "\n", tag, ##__VA_ARGS__);      \
    }                                                                      \
} while(0)

#define LIN_LOGE(env, tag, fmt, ...) do {                                 \
    if (env) {                                                             \
        char _lin_buf_[4096];                                              \
        snprintf(_lin_buf_, sizeof(_lin_buf_), fmt, ##__VA_ARGS__);        \
        lin_logger_log(env, LIN_LOG_ERROR, tag, _lin_buf_);                \
    } else {                                                               \
        fprintf(stderr, "ERROR [%s] " fmt "\n", tag, ##__VA_ARGS__);      \
    }                                                                      \
} while(0)

#ifdef __cplusplus
}
#endif

#endif /* LIN_LOGGER_H */

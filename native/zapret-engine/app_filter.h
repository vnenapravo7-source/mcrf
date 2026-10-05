#pragma once
#if defined(__CYGWIN__) || defined(MCRF_APP_FILTER_TEST)
#include "windows/windivert/windivert.h"
#include <stdbool.h>
struct mcrf_app_filter;
struct mcrf_app_filter *mcrf_apps_load(const char *file, bool exclude);
void mcrf_apps_free(struct mcrf_app_filter *filter);
bool mcrf_apps_start(void);
void mcrf_apps_stop(void);
void mcrf_apps_packet(const void *packet, unsigned length, const WINDIVERT_ADDRESS *address);
bool mcrf_apps_match(const struct mcrf_app_filter *filter);
bool mcrf_apps_known(void);
UINT64 mcrf_apps_endpoint(void);
#endif

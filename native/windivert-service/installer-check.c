/* Offline SCM contract test: no kernel-driver load, registry write or service operation. */
#define UNICODE
#include <winsock2.h>
#include <windows.h>
#include <stdio.h>
#include <wchar.h>
#include <string.h>

static int scenario, opens, creates, starts, deletes, closes, failures;
static void expect(int ok, const char *what) { if (!ok) { printf("FAIL %s\n", what); failures++; } }
static SC_HANDLE WINAPI fake_manager(LPCWSTR a,LPCWSTR b,DWORD c) { return (SC_HANDLE)1; }
static SC_HANDLE WINAPI fake_open(SC_HANDLE h,LPCWSTR name,DWORD access) {
    expect(wcscmp(name,L"MRCF_WD")==0,"OpenService exact MRCF_WD"); opens++;
    if (scenario==1 || opens>1) return (SC_HANDLE)2;
    SetLastError(ERROR_SERVICE_DOES_NOT_EXIST); return NULL;
}
static SC_HANDLE WINAPI fake_create(SC_HANDLE h,LPCWSTR name,LPCWSTR display,DWORD access,DWORD type,DWORD start,DWORD error,LPCWSTR path,LPCWSTR group,LPDWORD tag,LPCWSTR dep,LPCWSTR account,LPCWSTR password) {
    creates++; expect(wcscmp(name,L"MRCF_WD")==0 && wcscmp(display,L"MRCF_WD")==0,"CreateService exact name and display");
    expect(type==SERVICE_KERNEL_DRIVER && start==SERVICE_DEMAND_START,"upstream kernel demand-start contract");
    expect(wcsstr(path,L"WinDivert64.sys")!=NULL,"signed SYS filename unchanged");
    if (scenario==2) { SetLastError(ERROR_SERVICE_EXISTS); return NULL; }
    return (SC_HANDLE)2;
}
static BOOL WINAPI fake_start(SC_HANDLE h,DWORD n,LPCWSTR *args) { starts++; return TRUE; }
static BOOL WINAPI fake_delete(SC_HANDLE h) { deletes++; return TRUE; }
static BOOL WINAPI fake_close(SC_HANDLE h) { closes++; return TRUE; }
static LSTATUS WINAPI fake_registry(HKEY h,LPCSTR path,DWORD reserved,LPSTR cls,DWORD options,REGSAM access,const LPSECURITY_ATTRIBUTES attrs,PHKEY result,LPDWORD disposition) { return ERROR_ACCESS_DENIED; }
#define OpenSCManagerW fake_manager
#define OpenServiceW fake_open
#define CreateServiceW fake_create
#define StartServiceW fake_start
#define DeleteService fake_delete
#define CloseServiceHandle fake_close
#define RegCreateKeyExA fake_registry
#include "windivert.c"
int main(void) {
    int s; module=GetModuleHandleW(NULL);
    expect(wcscmp(WINDIVERT_DEVICE_NAME,L"WinDivert")==0,"internal device unchanged");
    for (s=0;s<3;s++) {
        scenario=s;opens=creates=starts=deletes=closes=0;
        expect(WinDivertDriverInstall(),"installer returns success with mocked SCM");
        expect(starts==1 && deletes==1 && closes==2,"upstream service lifecycle preserved");
        expect(creates==(s==1?0:1),"existing versus new service branches");
        expect(opens==(s==2?2:1),"create race reopens exact service");
    }
    printf("SCM_OFFLINE_%s scenarios=3 failures=%d; no real SCM or registry writes\n",failures?"FAIL":"PASS",failures);
    return failures?1:0;
}

/* Windows runtime smoke test: opens a window, writes smoke-ok.txt (with its working folder and own path) next to
 * itself, closes after 1.5 s. */
#include <windows.h>
static LRESULT CALLBACK proc(HWND h, UINT m, WPARAM w, LPARAM l) {
    if (m == WM_DESTROY) { PostQuitMessage(0); return 0; }
    return DefWindowProcA(h, m, w, l);
}
int WINAPI WinMain(HINSTANCE inst, HINSTANCE prev, LPSTR cmd, int show) {
    WNDCLASSA wc = {0};
    wc.lpfnWndProc = proc; wc.hInstance = inst; wc.lpszClassName = "AceSmoke";
    RegisterClassA(&wc);
    HWND h = CreateWindowA("AceSmoke", "AceArcade smoke test", WS_OVERLAPPEDWINDOW | WS_VISIBLE,
                           0, 0, 320, 240, NULL, NULL, inst, NULL);
    char text[640], cwd[MAX_PATH] = "", self[MAX_PATH] = "";
    GetCurrentDirectoryA(MAX_PATH, cwd);
    GetModuleFileNameA(NULL, self, MAX_PATH);
    wsprintfA(text, "ok %d-bit window=%d cwd=%s exe=%s", (int)(sizeof(void *) * 8), h != NULL, cwd, self);
    HANDLE f = CreateFileA("smoke-ok.txt", GENERIC_WRITE, 0, NULL, CREATE_ALWAYS, 0, NULL);
    DWORD n; WriteFile(f, text, lstrlenA(text), &n, NULL); CloseHandle(f);
    SetTimer(h, 1, 1500, NULL);
    MSG msg;
    while (GetMessageA(&msg, NULL, 0, 0) > 0) {
        if (msg.message == WM_TIMER) DestroyWindow(h);
        TranslateMessage(&msg); DispatchMessageA(&msg);
    }
    return 0;
}

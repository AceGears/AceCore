/* Windows runtime smoke test: opens a window, says so in smoke-window.txt, waits up to 10 minutes for the Enter key
 * (sent through the VNC display by smoke-test.sh, with the mouse pointer outside the window), then writes
 * smoke-ok.txt (bitness, window, working folder, own path, whether Enter arrived) next to itself and closes. */
#include <windows.h>
static int got_enter;
static LRESULT CALLBACK proc(HWND h, UINT m, WPARAM w, LPARAM l) {
    if (m == WM_KEYDOWN && w == VK_RETURN) { got_enter = 1; DestroyWindow(h); return 0; }
    if (m == WM_TIMER) { DestroyWindow(h); return 0; }
    if (m == WM_DESTROY) { PostQuitMessage(0); return 0; }
    return DefWindowProcA(h, m, w, l);
}
static void put(const char *name, const char *text) {
    HANDLE f = CreateFileA(name, GENERIC_WRITE, 0, NULL, CREATE_ALWAYS, 0, NULL);
    DWORD n; WriteFile(f, text, lstrlenA(text), &n, NULL); CloseHandle(f);
}
int WINAPI WinMain(HINSTANCE inst, HINSTANCE prev, LPSTR cmd, int show) {
    WNDCLASSA wc = {0};
    wc.lpfnWndProc = proc; wc.hInstance = inst; wc.lpszClassName = "AceSmoke";
    RegisterClassA(&wc);
    HWND h = CreateWindowA("AceSmoke", "AceArcade smoke test", WS_OVERLAPPEDWINDOW | WS_VISIBLE,
                           0, 0, 320, 240, NULL, NULL, inst, NULL);
    char cwd[MAX_PATH] = "", self[MAX_PATH] = "", text[640];
    GetCurrentDirectoryA(MAX_PATH, cwd);
    GetModuleFileNameA(NULL, self, MAX_PATH);
    put("smoke-window.txt", "shown");
    SetTimer(h, 1, 600000, NULL);
    MSG msg;
    while (GetMessageA(&msg, NULL, 0, 0) > 0) { TranslateMessage(&msg); DispatchMessageA(&msg); }
    wsprintfA(text, "ok %d-bit window=%d cwd=%s exe=%s enter=%d", (int)(sizeof(void *) * 8), h != NULL, cwd, self, got_enter);
    put("smoke-ok.txt", text);
    return 0;
}

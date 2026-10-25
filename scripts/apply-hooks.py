#!/usr/bin/env python3
"""
The complete list of WG Tunnel X changes inside upstream files ("hooks").

Each hook is idempotent: it is applied when missing and left alone when present. During an
upstream merge, a conflicted hook file is taken from upstream and the hooks are applied again, so
upstream refactorings around the hooks rarely need a human.

    scripts/apply-hooks.py            apply missing hooks, print what changed
    scripts/apply-hooks.py --check    exit 1 when a hook is missing
    scripts/apply-hooks.py FILE...    only hooks of these files
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
APP = "app/src/main/java/com/zaneschepke/wireguardautotunnel"


class HookError(Exception):
    pass


def add_import(text, imp):
    line = f"import {imp}\n"
    if line in text:
        return text
    imports = list(re.finditer(r"^import [^\n]+\n", text, re.M))
    if not imports:
        raise HookError(f"no import block for {imp}")
    # keep the block sorted the way ktfmt does
    for m in imports:
        if m.group(0) > line:
            return text[: m.start()] + line + text[m.start() :]
    last = imports[-1]
    return text[: last.end()] + line + text[last.end() :]


def append_line(text, line):
    if line in text:
        return text
    return text.rstrip("\n") + "\n\n" + line + "\n"


def hook_app_build(text):
    return append_line(text, 'apply(from = "wgtunnelx.gradle")')


def hook_settings(text):
    return append_line(text, 'include(":xray")')


def hook_application(text):
    text = hook_application_process(text)
    text = add_import(text, "com.zaneschepke.wireguardautotunnel.xray.xrayModule")
    if re.search(r"^\s*xrayModule,\s*$", text, re.M):
        return text
    m = re.search(r"modules\((?P<body>[^)]*?)(?P<indent>\n\s*)\)", text, re.S)
    if not m:
        raise HookError("Koin modules(...) list not found")
    indent = re.search(r"\n(\s*)\S", m.group("body"))
    pad = indent.group(1) if indent else "    "
    body = m.group("body").rstrip()
    if not body.endswith(","):
        body += ","
    new = f"modules({body}\n{pad}xrayModule,{m.group('indent')})"
    return text[: m.start()] + new + text[m.end() :]


def insert_after(text, anchor_re, line, what):
    """Puts `line` right after the line matching `anchor_re`, with the same indentation."""
    m = re.search(anchor_re, text, re.M)
    if not m:
        raise HookError(f"{what} not found")
    indent = re.match(r"[ \t]*", text[text.rfind("\n", 0, m.start()) + 1 :]).group(0)
    end = text.index("\n", m.end()) + 1
    return text[:end] + f"{indent}{line}\n" + text[end:]


def hook_application_process(text):
    # the :xray process (Xray-core only) must skip the app startup, see XrayProcess
    text = add_import(text, "com.wgtunnelx.xray.XrayProcess")
    if "XrayProcess.isolate(this)" not in text:
        text = insert_after(
            text,
            r"^\s*super\.onCreate\(\)\s*$",
            "if (XrayProcess.isolate(this)) return",
            "super.onCreate() in Application.onCreate",
        )
    if "XrayProcess.isXray(this)" not in text and "super.onTrimMemory(level)" in text:
        text = insert_after(
            text,
            r"^\s*super\.onTrimMemory\(level\)\s*$",
            "if (XrayProcess.isXray(this)) return",
            "super.onTrimMemory(level)",
        )
    return text


def hook_logcat(text):
    # Xray logs from its own process: read the whole app (logd only serves the app's own UID)
    if "--pid=" not in text:
        return text
    new = re.sub(r'^[ \t]*append\(" --pid=\$pid"\)\n', "", text, flags=re.M)
    if new == text:
        raise HookError("logcat --pid filter not found")
    return new


def hook_network(text):
    if "BuildConfig.X_UPDATE_OWNER" in text:
        return text
    exact = re.compile(
        r'^(?P<indent>[ \t]*)GitHubUpdateRepository\(\s*get\(\),\s*"wgtunnel",\s*"android",'
        r"\s*(?P<last>get\(named\(Dispatcher\.IO\)\)),?\s*\)",
        re.M,
    )
    m = exact.search(text)
    if m:
        i = m.group("indent")
        args = ["get()", "BuildConfig.X_UPDATE_OWNER", "BuildConfig.X_UPDATE_REPO", m.group("last")]
        call = f"{i}GitHubUpdateRepository(\n" + "".join(f"{i}    {a},\n" for a in args) + f"{i})"
        text = text[: m.start()] + call + text[m.end() :]
    else:
        loose = re.compile(r'GitHubUpdateRepository\(\s*get\(\),\s*"wgtunnel",\s*"android",')
        if not loose.search(text):
            raise HookError('GitHubUpdateRepository(get(), "wgtunnel", "android", ...) not found')
        text = loose.sub(
            "GitHubUpdateRepository(get(), BuildConfig.X_UPDATE_OWNER, BuildConfig.X_UPDATE_REPO,",
            text,
        )
    return add_import(text, "com.zaneschepke.wireguardautotunnel.BuildConfig")


def hook_settings_screen(text):
    text = add_import(text, "com.zaneschepke.wireguardautotunnel.xray.ui.XrayRoutingSettingsRow")
    if "XrayRoutingSettingsRow()" in text:
        return text
    # right after the "backend mode" row, which is the first SurfaceRow of the tunnel group
    anchor = text.find("R.string.backend_mode")
    if anchor < 0:
        raise HookError("backend_mode row not found")
    start = text.rfind("SurfaceRow(", 0, anchor)
    if start < 0:
        raise HookError("SurfaceRow of backend_mode not found")
    depth, i = 0, text.index("(", start)
    while i < len(text):
        if text[i] == "(":
            depth += 1
        elif text[i] == ")":
            depth -= 1
            if depth == 0:
                break
        i += 1
    line_start = text.rfind("\n", 0, start) + 1
    indent = text[line_start:start]
    end = i + 1
    return text[:end] + f"\n{indent}XrayRoutingSettingsRow()" + text[end:]


HOOKS = {
    "app/build.gradle.kts": hook_app_build,
    "settings.gradle.kts": hook_settings,
    f"{APP}/WireGuardAutoTunnel.kt": hook_application,
    f"{APP}/di/NetworkModule.kt": hook_network,
    f"{APP}/ui/screens/settings/SettingsScreen.kt": hook_settings_screen,
    "logcatter/src/main/java/com/zaneschepke/logcatter/LogcatStreamReader.kt": hook_logcat,
}


def main(argv):
    check = "--check" in argv
    files = [a for a in argv if not a.startswith("--")]
    selected = {k: v for k, v in HOOKS.items() if not files or k in files}
    status = 0
    for rel, hook in selected.items():
        path = ROOT / rel
        try:
            old = path.read_text()
            new = hook(old)
        except (HookError, FileNotFoundError) as e:
            print(f"FAILED {rel}: {e}")
            status = 1
            continue
        if new == old:
            print(f"ok      {rel}")
        elif check:
            print(f"MISSING {rel}")
            status = 1
        else:
            path.write_text(new)
            print(f"applied {rel}")
    return status


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

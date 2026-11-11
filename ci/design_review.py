"""Sends the latest app screenshots and ci/design-brief.md to Gemini and prints its review."""
import base64, glob, json, os, sys, urllib.request

KEY = os.environ.get("GEMINI_API_KEY", "")
API = "https://generativelanguage.googleapis.com/v1beta"


def call(url, body=None):
    req = urllib.request.Request(url, data=json.dumps(body).encode() if body else None,
                                 headers={"Content-Type": "application/json", "x-goog-api-key": KEY})
    with urllib.request.urlopen(req, timeout=300) as r:
        return json.load(r)


def pick_model():
    wanted = os.environ.get("GEMINI_MODEL", "").strip()
    if wanted:
        return wanted
    names = [m["name"].split("/")[-1] for m in call(f"{API}/models?pageSize=200").get("models", [])
             if "generateContent" in m.get("supportedGenerationMethods", [])]
    skip = ("tts", "image", "embedding", "audio", "live", "lite", "nano", "robotics", "computer")
    pro = sorted(n for n in names if n.startswith("gemini") and "pro" in n and not any(s in n for s in skip))
    flash = sorted(n for n in names if n.startswith("gemini") and "flash" in n and not any(s in n for s in skip))
    return (pro or flash or names)[-1]


def main():
    if not KEY:
        print("No GEMINI_API_KEY secret is set on the repo, so there was nothing to ask.")
        return
    order = ["home_screen", "light_home", "settings_tab", "settings_voice_page", "notes_tab", "whisper_tab",
             "voice_picker", "assistant_gesture_screen"]
    shots = [f"shots/{n}.png" for n in order if os.path.exists(f"shots/{n}.png")][:8] or sorted(glob.glob("shots/*.png"))[:8]
    parts = [{"text": open("ci/design-brief.md").read()}]
    for s in shots:
        parts.append({"text": f"Screenshot: {os.path.basename(s)}"})
        parts.append({"inline_data": {"mime_type": "image/png", "data": base64.b64encode(open(s, "rb").read()).decode()}})
    model = pick_model()
    out = call(f"{API}/models/{model}:generateContent", {"contents": [{"role": "user", "parts": parts}]})
    text = "".join(p.get("text", "") for p in out["candidates"][0]["content"]["parts"])
    print(f"_Gemini ({model}), reviewing {len(shots)} screenshots._\n\n{text}")


if __name__ == "__main__":
    try:
        main()
    except Exception as e:  # the workflow posts whatever is printed
        print(f"The review failed: {e}")
        sys.exit(0)

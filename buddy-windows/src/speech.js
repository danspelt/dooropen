const { spawn } = require("child_process");
const path = require("path");
const readline = require("readline");

let child = null;
let onCommand = null;

function start(handler) {
  if (child) return;
  onCommand = handler;

  const script = path.join(__dirname, "..", "powershell", "listen.ps1");
  child = spawn(
    "powershell.exe",
    ["-NoProfile", "-ExecutionPolicy", "Bypass", "-File", script],
    { windowsHide: true }
  );

  const rl = readline.createInterface({ input: child.stdout });
  rl.on("line", (line) => {
    try {
      const payload = JSON.parse(line);
      if (payload.recognized && onCommand) {
        onCommand(payload.recognized);
      }
    } catch {
      // ignore non-JSON lines
    }
  });

  child.stderr.on("data", (chunk) => {
    const text = chunk.toString().trim();
    if (text) console.warn("[buddy-speech]", text);
  });

  child.on("exit", (code) => {
    child = null;
    if (code !== 0 && code !== null) {
      console.warn(`[buddy-speech] listener exited (${code}), restarting in 5s`);
      setTimeout(() => start(handler), 5000);
    }
  });

  console.log("[buddy] Windows speech listener started");
}

function stop() {
  if (!child) return;
  child.kill();
  child = null;
  onCommand = null;
}

function parseBuddyCommand(text) {
  const lower = text.toLowerCase().trim();
  if (lower.includes("buddy phone")) return { action: "headset", target: "phone" };
  if (lower.includes("buddy computer")) return { action: "headset", target: "computer" };
  if (lower.includes("buddy hang up") || lower.includes("buddy, hang up")) {
    return { action: "hangup" };
  }
  const callMatch = lower.match(/buddy\s*,?\s*call\s+(.+)/i);
  if (callMatch) {
    return { action: "call", contactName: callMatch[1].trim() || null };
  }
  return null;
}

module.exports = { start, stop, parseBuddyCommand };

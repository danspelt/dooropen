const { spawn } = require("child_process");
const path = require("path");

const PS = "powershell.exe";
const PS_ARGS = ["-NoProfile", "-ExecutionPolicy", "Bypass", "-File"];

function scriptPath(name) {
  return path.join(__dirname, "..", "powershell", name);
}

function runScript(script, args = [], timeoutMs = 30_000) {
  return new Promise((resolve, reject) => {
    const child = spawn(PS, [...PS_ARGS, scriptPath(script), ...args], {
      windowsHide: true,
    });

    let stdout = "";
    let stderr = "";

    const timer = setTimeout(() => {
      child.kill();
      reject(new Error(`${script} timed out after ${timeoutMs}ms`));
    }, timeoutMs);

    child.stdout.on("data", (chunk) => {
      stdout += chunk.toString();
    });
    child.stderr.on("data", (chunk) => {
      stderr += chunk.toString();
    });

    child.on("error", (err) => {
      clearTimeout(timer);
      reject(err);
    });

    child.on("close", (code) => {
      clearTimeout(timer);
      if (code !== 0) {
        reject(new Error(stderr.trim() || `${script} exited with code ${code}`));
        return;
      }
      resolve(stdout.trim());
    });
  });
}

function speak(text) {
  return runScript("speak.ps1", ["-Text", text], 60_000).catch((err) => {
    console.warn("[buddy] TTS failed:", err.message);
  });
}

module.exports = { runScript, speak };

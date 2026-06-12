const { runScript, speak } = require("./powershell");
const fs = require("fs");
const path = require("path");

const CONFIG_PATH = path.join(__dirname, "..", "buddy-config.json");

let currentMode = "computer";
let statusMessage = "Ready";
let selectedHeadset = "OpenRun Pro by Shokz";
let lastDetail = "";

function loadConfig() {
  try {
    const data = JSON.parse(fs.readFileSync(CONFIG_PATH, "utf8"));
    if (data.selectedHeadset) selectedHeadset = data.selectedHeadset;
  } catch (_) {}
}

function saveConfig() {
  try {
    fs.writeFileSync(
      CONFIG_PATH,
      JSON.stringify({ selectedHeadset }, null, 2),
      "utf8"
    );
  } catch (_) {}
}

loadConfig();

function getMode() {
  return currentMode;
}

function getStatus() {
  return {
    mode: currentMode,
    status: statusMessage,
    detail: lastDetail,
    selectedHeadset,
    statusLabel:
      currentMode === "phone"
        ? "Headset moved to phone"
        : "Headset moved to computer",
  };
}

function setSelectedHeadset(name) {
  selectedHeadset = String(name || "").trim() || selectedHeadset;
  saveConfig();
}

function parseRouteResult(raw) {
  const parts = String(raw || "").trim().split("|");
  return {
    mode: parts[0] || "",
    audio: parts[1] || "",
    bt: parts[2] || "",
  };
}

async function routeHeadset(target) {
  const headsetName = selectedHeadset || "OpenRun";
  const psTarget = target === "phone" ? "Phone" : "Computer";
  const raw = await runScript(
    "audio-route.ps1",
    ["-Target", psTarget, "-HeadsetName", headsetName],
    45_000
  );
  const result = parseRouteResult(raw);
  lastDetail = raw;

  if (target === "phone") {
    currentMode = "phone";
    statusMessage = "Headset moved to phone. Windows Bluetooth is off.";
    if (result.audio && result.audio !== "no-speakers") {
      statusMessage += ` PC audio: ${result.audio}.`;
    }
    return { mode: "phone", message: statusMessage, detail: raw };
  }

  currentMode = "computer";
  statusMessage = "Headset moved to computer. Windows Bluetooth is on.";
  if (result.audio && result.audio !== "no-headset") {
    statusMessage += ` Headset audio: ${result.audio}.`;
  } else {
    statusMessage += " Pick your headset in Sound settings if needed.";
  }
  return { mode: "computer", message: statusMessage, detail: raw };
}

async function switchHeadset(target, options = {}) {
  const { speak: useSpeech = false } = options;

  if (target !== "phone" && target !== "computer") {
    throw new Error(`Unknown target: ${target}`);
  }

  const result = await routeHeadset(target);
  if (useSpeech) {
    await speak(target === "phone" ? "Phone" : "Computer");
  }
  return result;
}

module.exports = {
  getMode,
  getStatus,
  setSelectedHeadset,
  switchHeadset,
  routeHeadset,
};

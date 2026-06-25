const VALID_TARGETS = new Set(["phone", "computer"]);

function parseMessage(raw) {
  let json;
  try {
    json = JSON.parse(raw);
  } catch {
    return null;
  }
  if (!json || typeof json.type !== "string") return null;
  return json;
}

function isHeadsetSwitch(msg) {
  return msg.type === "MODE_SWITCH" || msg.type === "HEADSET_SWITCH";
}

function headsetTarget(msg) {
  const target = String(msg.target || "").toLowerCase();
  return VALID_TARGETS.has(target) ? target : null;
}

function statusPayload(mode, doorReady = true) {
  return JSON.stringify({
    type: "STATUS",
    mode,
    doorReady,
    version: "1",
  });
}

function modeAck(mode) {
  return JSON.stringify({ type: "MODE_ACK", mode });
}

function ping() {
  return JSON.stringify({ type: "PING" });
}

function speakMessage(text) {
  return JSON.stringify({ type: "SPEAK", message: text });
}

function openDoor() {
  return JSON.stringify({ type: "OPEN_DOOR" });
}

module.exports = {
  parseMessage,
  isHeadsetSwitch,
  headsetTarget,
  statusPayload,
  modeAck,
  ping,
  speakMessage,
  openDoor,
};

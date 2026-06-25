const http = require("http");
const { WebSocketServer, WebSocket } = require("ws");
const headset = require("./headset");
const protocol = require("./protocol");
const { serveStatic, handleApi } = require("./api");

const PORT = Number(process.env.BUDDY_PORT || 8765);
const PING_INTERVAL_MS = 60_000;

let phoneSocket = null;
let pingTimer = null;
let switching = false;

function createServer() {
  const server = http.createServer(async (req, res) => {
    try {
      const handled = await handleApi(req, res, headset);
      if (handled !== false) return;
      if (serveStatic(req, res)) return;
      res.writeHead(404, { "Content-Type": "text/plain" });
      res.end("Not found");
    } catch (err) {
      console.error("[buddy] HTTP error:", err.message);
      res.writeHead(500, { "Content-Type": "text/plain" });
      res.end("Server error");
    }
  });

  const wss = new WebSocketServer({ noServer: true });

  server.on("error", (err) => {
    console.error("[buddy] HTTP server error:", err.message);
  });

  wss.on("error", (err) => {
    console.warn("[buddy] WebSocket server error:", err.message);
  });

  server.on("upgrade", (req, socket, head) => {
    socket.on("error", (err) => {
      console.warn("[buddy] Upgrade socket error:", err.message);
    });
    wss.handleUpgrade(req, socket, head, (ws) => {
      wss.emit("connection", ws, req);
    });
  });

  wss.on("connection", (ws) => {
    if (phoneSocket && phoneSocket.readyState === WebSocket.OPEN) {
      console.log("[buddy] Replacing previous phone connection");
      phoneSocket.close();
    }

    phoneSocket = ws;
    console.log("[buddy] Phone connected");

    ws.on("message", (data) => {
      handlePhoneMessage(data.toString()).catch((err) => {
        console.error("[buddy] Message handler error:", err.message);
      });
    });

    ws.on("close", () => {
      if (phoneSocket === ws) {
        phoneSocket = null;
        stopPing();
        console.log("[buddy] Phone disconnected");
      }
    });

    ws.on("error", (err) => {
      console.warn("[buddy] Socket error:", err.message);
      try {
        ws.close();
      } catch (_) {}
    });

    startPing();
  });

  server.listen(PORT, "0.0.0.0", () => {
    console.log(`[buddy] Headset Control UI: http://localhost:${PORT}`);
    console.log(`[buddy] WebSocket (phone bridge): port ${PORT}`);
  });

  return { server, wss };
}

async function handlePhoneMessage(raw) {
  const msg = protocol.parseMessage(raw);
  if (!msg) return;

  switch (msg.type) {
    case "STATUS":
      console.log(
        `[buddy] Phone status: mode=${msg.mode} doorReady=${msg.doorReady} v=${msg.version}`
      );
      break;

    case "MODE_SWITCH":
    case "HEADSET_SWITCH":
      await handleHeadsetSwitch(msg);
      break;

    case "PHONE_CALL":
      await handlePhoneCall(msg);
      break;

    case "PONG":
      break;

    case "ACK":
      console.log("[buddy] Phone ACK:", msg.message || "");
      break;

    default:
      console.log("[buddy] Unknown message type:", msg.type);
  }
}

async function handleHeadsetSwitch(msg) {
  if (switching) {
    console.log("[buddy] Ignoring switch — already in progress");
    return;
  }

  const target = protocol.headsetTarget(msg);
  if (!target) {
    console.warn("[buddy] Invalid headset target:", msg.target);
    return;
  }

  switching = true;
  try {
    const result = await headset.switchHeadset(target, { speak: true });
    sendToPhone(protocol.modeAck(result.mode));
  } catch (err) {
    console.error("[buddy] Headset switch failed:", err.message);
    sendToPhone(protocol.speakMessage("Headset switch failed."));
  } finally {
    switching = false;
  }
}

async function handlePhoneCall(msg) {
  const action = String(msg.action || "").toLowerCase();

  if (action === "call") {
    await handleHeadsetSwitch({ type: "HEADSET_SWITCH", target: "phone" });
    return;
  }

  if (action === "hangup") {
    setTimeout(() => {
      handleHeadsetSwitch({ type: "HEADSET_SWITCH", target: "computer" }).catch(
        (err) => console.error("[buddy] Post-hangup reconnect failed:", err.message)
      );
    }, 1000);
  }
}

function sendToPhone(text) {
  if (!phoneSocket || phoneSocket.readyState !== WebSocket.OPEN) {
    return false;
  }
  phoneSocket.send(text);
  return true;
}

function sendOpenDoor() {
  return sendToPhone(protocol.openDoor());
}

function startPing() {
  stopPing();
  pingTimer = setInterval(() => {
    sendToPhone(protocol.ping());
  }, PING_INTERVAL_MS);
}

function stopPing() {
  if (pingTimer) {
    clearInterval(pingTimer);
    pingTimer = null;
  }
}

function isPhoneConnected() {
  return phoneSocket !== null && phoneSocket.readyState === WebSocket.OPEN;
}

module.exports = {
  createServer,
  sendToPhone,
  sendOpenDoor,
  isPhoneConnected,
  handleHeadsetSwitch,
};

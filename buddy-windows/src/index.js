const server = require("./server");
const headset = require("./headset");

const { createServer } = server;

createServer();

const enableSpeech = process.env.BUDDY_SPEECH === "1";

if (enableSpeech) {
  const speech = require("./speech");
  speech.start(async (recognized) => {
    const cmd = speech.parseBuddyCommand(recognized);
    if (!cmd) return;

    console.log("[buddy] Heard:", recognized);

    if (cmd.action === "headset") {
      await server.handleHeadsetSwitch({
        type: "HEADSET_SWITCH",
        target: cmd.target,
      });
      return;
    }

    if (cmd.action === "call" || cmd.action === "hangup") {
      if (cmd.action === "call") {
        await server.handleHeadsetSwitch({
          type: "HEADSET_SWITCH",
          target: "phone",
        });
        if (cmd.contactName) {
          server.sendToPhone(
            JSON.stringify({
              type: "PHONE_CALL",
              action: "call",
              contactName: cmd.contactName,
            })
          );
        }
      } else {
        server.sendToPhone(
          JSON.stringify({ type: "PHONE_CALL", action: "hangup" })
        );
      }
    }
  });

  process.on("SIGINT", () => {
    console.log("\n[buddy] Shutting down");
    speech.stop();
    process.exit(0);
  });
} else {
  process.on("SIGINT", () => {
    console.log("\n[buddy] Shutting down");
    process.exit(0);
  });
}

process.on("uncaughtException", (err) => {
  console.error("[buddy] Uncaught error (staying alive):", err.message);
});

const port = process.env.BUDDY_PORT || 8765;
console.log("[buddy] Windows Headset Control ready");
console.log(`[buddy] Open http://localhost:${port} in your browser`);
console.log(`[buddy] Current mode: ${headset.getMode()}`);
console.log("[buddy] PHONE = Bluetooth off  |  COMPUTER = Bluetooth on + default audio");

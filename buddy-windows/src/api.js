const fs = require("fs");
const path = require("path");
const { runScript } = require("./powershell");

const PUBLIC_DIR = path.join(__dirname, "..", "public");
let uiSwitching = false;

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    req.on("data", (c) => chunks.push(c));
    req.on("end", () => {
      const raw = Buffer.concat(chunks).toString("utf8");
      if (!raw) return resolve({});
      try {
        resolve(JSON.parse(raw));
      } catch {
        resolve({});
      }
    });
    req.on("error", reject);
  });
}

function sendJson(res, status, data) {
  const body = JSON.stringify(data);
  res.writeHead(status, {
    "Content-Type": "application/json",
    "Cache-Control": "no-store",
  });
  res.end(body);
}

function serveStatic(req, res) {
  let urlPath = req.url.split("?")[0];
  if (urlPath === "/") urlPath = "/index.html";
  const filePath = path.normalize(path.join(PUBLIC_DIR, urlPath));
  if (!filePath.startsWith(PUBLIC_DIR)) {
    res.writeHead(403);
    res.end("Forbidden");
    return true;
  }
  if (!fs.existsSync(filePath) || fs.statSync(filePath).isDirectory()) {
    return false;
  }
  const ext = path.extname(filePath).toLowerCase();
  const types = {
    ".html": "text/html; charset=utf-8",
    ".css": "text/css; charset=utf-8",
    ".js": "application/javascript; charset=utf-8",
    ".svg": "image/svg+xml",
  };
  res.writeHead(200, { "Content-Type": types[ext] || "application/octet-stream" });
  fs.createReadStream(filePath).pipe(res);
  return true;
}

async function handleApi(req, res, headset) {
  const url = new URL(req.url, "http://localhost");

  if (req.method === "GET" && url.pathname === "/api/status") {
    return sendJson(res, 200, headset.getStatus());
  }

  if (req.method === "GET" && url.pathname === "/api/headsets") {
    try {
      const raw = await runScript("list-headsets.ps1", [], 20_000);
      const data = JSON.parse(raw);
      return sendJson(res, 200, data);
    } catch (err) {
      return sendJson(res, 500, { error: err.message, headsets: [] });
    }
  }

  if (req.method === "POST" && url.pathname === "/api/select") {
    const body = await readBody(req);
    headset.setSelectedHeadset(body.name || "");
    return sendJson(res, 200, headset.getStatus());
  }

  if (req.method === "POST" && url.pathname === "/api/switch") {
    const body = await readBody(req);
    const target = String(body.target || "").toLowerCase();
    if (target !== "phone" && target !== "computer") {
      return sendJson(res, 400, { error: "target must be phone or computer" });
    }
    if (uiSwitching) {
      return sendJson(res, 409, { error: "Switch already in progress" });
    }
    uiSwitching = true;
    try {
      const result = await headset.switchHeadset(target, { speak: false });
      return sendJson(res, 200, { ...result, ...headset.getStatus() });
    } catch (err) {
      return sendJson(res, 500, { error: err.message, ...headset.getStatus() });
    } finally {
      uiSwitching = false;
    }
  }

  return false;
}

module.exports = { serveStatic, handleApi, sendJson };

const http = require('http');
const fs = require('fs');
const path = require('path');
const { WebSocketServer } = require('ws');

const PORT = process.env.PORT || 8080;
const page = path.join(__dirname, 'public', 'index.html');

const server = http.createServer((req, res) => {
  if (req.url === '/healthz') { res.end('ok'); return; }
  fs.readFile(page, (err, data) => {
    if (err) { res.statusCode = 500; return res.end('error'); }
    res.setHeader('Content-Type', 'text/html; charset=utf-8');
    res.end(data);
  });
});

const wss = new WebSocketServer({ server, maxPayload: 4 * 1024 * 1024 });
const rooms = new Map(); // code -> { phone, ctrls:Set, info }

function getRoom(code) {
  if (!rooms.has(code)) rooms.set(code, { phone: null, ctrls: new Set(), info: { type: 'info', online: false } });
  return rooms.get(code);
}
function bcast(room, data, binary) {
  for (const c of room.ctrls) if (c.readyState === 1) c.send(data, { binary });
}

wss.on('connection', (ws) => {
  ws.alive = true;
  ws.on('pong', () => { ws.alive = true; });
  let role = null, room = null, code = null;
  const timer = setTimeout(() => { if (!role) ws.close(); }, 5000);

  ws.on('message', (data, isBinary) => {
    if (!role) {
      let m;
      try { m = JSON.parse(data.toString()); } catch { return ws.close(); }
      if (m.type !== 'hello' || !/^[A-Z0-9]{8}$/.test(m.code || '') || !['phone', 'controller'].includes(m.role)) return ws.close();
      role = m.role; code = m.code; room = getRoom(code);
      clearTimeout(timer);
      if (role === 'phone') {
        if (room.phone && room.phone.readyState === 1) room.phone.close();
        room.phone = ws;
        room.info = { type: 'info', online: true, w: m.w, h: m.h, a11y: !!m.a11y };
        bcast(room, JSON.stringify(room.info), false);
      } else {
        room.ctrls.add(ws);
        ws.send(JSON.stringify(room.info));
      }
      return;
    }
    if (role === 'phone') bcast(room, data, isBinary);
    else if (room.phone && room.phone.readyState === 1 && !isBinary) room.phone.send(data.toString());
  });

  ws.on('close', () => {
    clearTimeout(timer);
    if (!room) return;
    if (role === 'phone' && room.phone === ws) {
      room.phone = null;
      room.info = { type: 'info', online: false };
      bcast(room, JSON.stringify(room.info), false);
    } else if (role === 'controller') {
      room.ctrls.delete(ws);
    }
    if (!room.phone && room.ctrls.size === 0) rooms.delete(code);
  });
});

setInterval(() => {
  wss.clients.forEach((ws) => {
    if (!ws.alive) return ws.terminate();
    ws.alive = false;
    ws.ping();
  });
}, 30000);

server.listen(PORT, () => console.log('relay on :' + PORT));

// PC side of NsdDiscoveryTest's relay: carries the emulator's _notflix mDNS queries to the real LAN
// and Notflix answers back. Frames: 2-byte big-endian length + DNS message.
//   node scripts/mdns-relay.mjs <this PC's LAN IPv4>
//   gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.lanRelay=10.0.2.2:5399 ...
// Test tool only: the app itself doesn't use it. See NsdDiscoveryTest.
import dgram from "node:dgram";
import net from "node:net";
const lan = process.argv[2];
const GROUP = "224.0.0.251";
const udp = dgram.createSocket({ type: "udp4", reuseAddr: true });
const clients = new Set();
udp.on("message", (m, r) => {
  const isResponse = (m[2] & 0x80) !== 0;
  if (!isResponse || !m.toString("latin1").toLowerCase().includes("notflix")) return;
  console.log(`answer ${m.length} bytes from ${r.address}, to ${clients.size} client(s)`);
  const h = Buffer.alloc(2); h.writeUInt16BE(m.length);
  for (const c of clients) c.write(Buffer.concat([h, m]));
});
udp.bind(5353, "0.0.0.0", () => { udp.addMembership(GROUP, lan); udp.setMulticastInterface(lan); console.log("udp ready on", lan); });
net.createServer((s) => {
  clients.add(s); console.log("emulator connected");
  let buf = Buffer.alloc(0);
  s.on("data", (d) => {
    buf = Buffer.concat([buf, d]);
    while (buf.length >= 2 && buf.length >= 2 + buf.readUInt16BE(0)) {
      const n = buf.readUInt16BE(0); const q = buf.subarray(2, 2 + n); buf = buf.subarray(2 + n);
      udp.send(q, 5353, GROUP); console.log(`query ${n} bytes sent to LAN`);
    }
  });
  s.on("close", () => clients.delete(s)); s.on("error", () => clients.delete(s));
}).listen(5399, "127.0.0.1", () => console.log("tcp ready"));

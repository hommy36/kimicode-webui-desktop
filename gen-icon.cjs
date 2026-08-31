// 生成一张 512x512 的源图标（深蓝底 + 蓝色圆），供 `npx tauri icon` 使用
const zlib = require("zlib");
const fs = require("fs");

const size = 512;
const raw = Buffer.alloc((size * 4 + 1) * size);
let i = 0;
for (let y = 0; y < size; y++) {
  raw[i++] = 0; // filter: none
  for (let x = 0; x < size; x++) {
    const dx = x - size / 2;
    const dy = y - size / 2;
    const r = Math.sqrt(dx * dx + dy * dy);
    let R, G, B, A;
    if (r < size * 0.3) {
      R = 79; G = 156; B = 249; A = 255; // 蓝色圆
    } else {
      R = 26; G = 27; B = 30; A = 255; // 深色底
    }
    raw[i++] = R; raw[i++] = G; raw[i++] = B; raw[i++] = A;
  }
}

function chunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length);
  const t = Buffer.from(type);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(zlib.crc32(Buffer.concat([t, data])) >>> 0);
  return Buffer.concat([len, t, data, crc]);
}

const ihdr = Buffer.alloc(13);
ihdr.writeUInt32BE(size, 0);
ihdr.writeUInt32BE(size, 4);
ihdr[8] = 8; // bit depth
ihdr[9] = 6; // color type RGBA

const png = Buffer.concat([
  Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]),
  chunk("IHDR", ihdr),
  chunk("IDAT", zlib.deflateSync(raw)),
  chunk("IEND", Buffer.alloc(0)),
]);

fs.mkdirSync("src-tauri", { recursive: true });
fs.writeFileSync("src-tauri/app-icon.png", png);
console.log("wrote src-tauri/app-icon.png");

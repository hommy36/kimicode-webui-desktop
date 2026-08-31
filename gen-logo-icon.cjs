// 按 WebUI logo 重绘 512x512 应用图标（蓝圆角矩形 + 两个深色竖条），4x 超采样抗锯齿
const zlib = require("zlib");
const fs = require("fs");

const size = 512;
const SS = 4; // 超采样倍数

// 主体：圆角矩形 (32,106,448,300) r=80；颜色采样自 WebUI：#1A88FF
const R = { x: 32, y: 106, w: 448, h: 300, r: 80 };
// 竖条：宽 37 高 120，中心 (208,256) / (304,256)，圆角 18.5；颜色 #121212
const bars = [208, 304].map((cx) => ({ x: cx - 18.5, y: 196, w: 37, h: 120, r: 18.5 }));

function inRoundRect(px, py, rr) {
  if (px < rr.x || px >= rr.x + rr.w || py < rr.y || py >= rr.y + rr.h) return false;
  const cx = Math.min(Math.max(px, rr.x + rr.r), rr.x + rr.w - rr.r);
  const cy = Math.min(Math.max(py, rr.y + rr.r), rr.y + rr.h - rr.r);
  const dx = px - cx, dy = py - cy;
  return dx * dx + dy * dy <= rr.r * rr.r;
}

const raw = Buffer.alloc((size * 4 + 1) * size);
let i = 0;
for (let y = 0; y < size; y++) {
  raw[i++] = 0;
  for (let x = 0; x < size; x++) {
    let covBody = 0, covBar = 0;
    for (let sy = 0; sy < SS; sy++) {
      for (let sx = 0; sx < SS; sx++) {
        const px = x + (sx + 0.5) / SS, py = y + (sy + 0.5) / SS;
        if (inRoundRect(px, py, R)) covBody++;
        if (bars.some((b) => inRoundRect(px, py, b))) covBar++;
      }
    }
    const n = SS * SS;
    let rC = 0, gC = 0, bC = 0, aC = 0;
    const aBody = covBody / n, aBar = covBar / n;
    // 先铺蓝色主体，再盖深色竖条（竖条在主体内部，取两者覆盖率较大者混合）
    rC = 0x1a; gC = 0x88; bC = 0xff; aC = aBody;
    if (aBar > 0) {
      const t = aBar;
      rC = rC * (1 - t) + 0x12 * t;
      gC = gC * (1 - t) + 0x12 * t;
      bC = bC * (1 - t) + 0x12 * t;
      aC = Math.max(aBody, aBar);
    }
    raw[i++] = Math.round(rC);
    raw[i++] = Math.round(gC);
    raw[i++] = Math.round(bC);
    raw[i++] = Math.round(aC * 255);
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
ihdr[8] = 8;
ihdr[9] = 6;

const png = Buffer.concat([
  Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]),
  chunk("IHDR", ihdr),
  chunk("IDAT", zlib.deflateSync(raw)),
  chunk("IEND", Buffer.alloc(0)),
]);

fs.writeFileSync("src-tauri/app-icon.png", png);
console.log("wrote src-tauri/app-icon.png");

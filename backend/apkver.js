'use strict';
// APK (zip) ke andar se AndroidManifest.xml nikaal ke uska versionCode /
// versionName padhta hai — bina kisi npm dependency ke, pure Buffer parsing.
// Isse:
//   1. Manual upload par bhi server ko APK ka version pata chal jata hai
//      (version-based sync compare + "Server par APK: v1.8 (build 9)" display).
//   2. GitHub sync manual-override ko VERSION ke hisaab se faisla karne lagta
//      hai — purani APK naye timestamp ke saath upload ho to bhi sync jeetega.
//
// Zip: EOCD (0x06054b50) → central directory entries → AndroidManifest.xml
// (method 8 = raw-deflate, 0 = stored). AXML: string pool (0x0001) →
// start-element (0x0102) chunks; <manifest> pehla element hota hai.

const zlib = require('zlib');

const RES_STRING_POOL_TYPE = 0x0001;
const RES_XML_START_ELEMENT_TYPE = 0x0102;
const UTF8_FLAG = 0x100;
const NO_STRING = 0xffffffff;
const MANIFEST = 'AndroidManifest.xml';

// Central directory se zip entry ka uncompressed Buffer nikaalo (na mile null).
function zipEntry(buf, wantName) {
  try {
    // EOCD end se dhoondo (comment ho sakta hai)
    let eocd = -1;
    for (let i = buf.length - 22; i >= Math.max(0, buf.length - 22 - 65536); i--) {
      if (buf.readUInt32LE(i) === 0x06054b50) { eocd = i; break; }
    }
    if (eocd < 0) return null;
    const count = buf.readUInt16LE(eocd + 10);
    let p = buf.readUInt32LE(eocd + 16);
    for (let i = 0; i < count; i++) {
      if (p + 46 > buf.length || buf.readUInt32LE(p) !== 0x02014b50) return null;
      const method = buf.readUInt16LE(p + 10);
      const csize = buf.readUInt32LE(p + 20);
      const nameLen = buf.readUInt16LE(p + 28);
      const extraLen = buf.readUInt16LE(p + 30);
      const commentLen = buf.readUInt16LE(p + 32);
      const localOff = buf.readUInt32LE(p + 42);
      const name = buf.slice(p + 46, p + 46 + nameLen).toString('utf8');
      if (name === wantName) {
        // Local header se data start nikaalo (extra len local header me alag ho sakta hai)
        if (localOff + 30 > buf.length || buf.readUInt32LE(localOff) !== 0x04034b50) return null;
        const lNameLen = buf.readUInt16LE(localOff + 26);
        const lExtraLen = buf.readUInt16LE(localOff + 28);
        const dataAt = localOff + 30 + lNameLen + lExtraLen;
        const raw = buf.slice(dataAt, dataAt + csize);
        if (method === 0) return raw;
        if (method === 8) return zlib.inflateRawSync(raw);
        return null;
      }
      p += 46 + nameLen + extraLen + commentLen;
    }
    return null;
  } catch (e) {
    return null;
  }
}

// String pool chunk parse karke strings[] return karo (utf8 + utf16 dono).
// dhyan: utf8 pool me length 1-byte-varint (high-bit continuation), utf16 pool
// me length 2-byte (u16, 0x8000 continuation) — dono alag readers.
function parseStringPool(buf, off) {
  const hdrSize = buf.readUInt16LE(off + 2);
  const stringCount = buf.readUInt32LE(off + 8);
  const flags = buf.readUInt32LE(off + 16);
  const stringsStart = buf.readUInt32LE(off + 20);
  const utf8 = (flags & UTF8_FLAG) !== 0;
  const offsetsAt = off + hdrSize;
  const out = [];
  for (let i = 0; i < stringCount; i++) {
    const so = off + stringsStart + buf.readUInt32LE(offsetsAt + i * 4);
    if (utf8) {
      const [, p1] = readVarint8(buf, so);       // utf16 char count (ignore)
      const [byteLen, p2] = readVarint8(buf, p1); // utf8 byte count
      out.push(buf.slice(p2, p2 + byteLen).toString('utf8'));
    } else {
      const [charLen, p1] = readVarint16(buf, so); // utf16 char count
      out.push(buf.slice(p1, p1 + charLen * 2).toString('utf16le'));
    }
  }
  return out;
}

function readVarint8(buf, p) {
  const b = buf[p];
  if (b === undefined) return [0, p + 1];
  if ((b & 0x80) === 0) return [b, p + 1];
  return [((b & 0x7f) << 8) | buf[p + 1], p + 2];
}

function readVarint16(buf, p) {
  const b = buf.readUInt16LE(p);
  if ((b & 0x8000) === 0) return [b, p + 2];
  return [((b & 0x7fff) << 16) | buf.readUInt16LE(p + 2), p + 4];
}

// Binary AXML se versionCode/versionName.
function axmlVersion(buf) {
  if (!buf || buf.length < 8 || buf.readUInt16LE(0) !== 0x0003) return null;
  let off = 8;
  let strings = null;
  while (off + 8 <= buf.length) {
    const type = buf.readUInt16LE(off);
    const size = buf.readUInt32LE(off + 4);
    if (size < 8 || off + size > buf.length) return null;
    if (type === RES_STRING_POOL_TYPE) { strings = parseStringPool(buf, off); }
    if (type === RES_XML_START_ELEMENT_TYPE) break;
    off += size;
  }
  if (!strings || !strings.length) return null;
  const vcIdx = strings.indexOf('versionCode');
  const vnIdx = strings.indexOf('versionName');
  if (vcIdx < 0 && vnIdx < 0) return null;

  let versionCode = null, versionName = null;
  while (off + 8 <= buf.length) {
    const type = buf.readUInt16LE(off);
    const size = buf.readUInt32LE(off + 4);
    if (size < 8 || off + size > buf.length) break;
    if (type === RES_XML_START_ELEMENT_TYPE) {
      const attributeStart = buf.readUInt16LE(off + 24);
      const attributeSize = buf.readUInt16LE(off + 26);
      const attributeCount = buf.readUInt16LE(off + 28);
      const first = off + 16 + attributeStart;
      for (let i = 0; i < attributeCount; i++) {
        const a = first + i * attributeSize;
        if (a + 20 > off + size) break;
        const nameIdx = buf.readUInt32LE(a + 4);
        const rawValue = buf.readUInt32LE(a + 8);
        const dataType = buf[a + 15];
        const data = buf.readUInt32LE(a + 16);
        if (nameIdx === vcIdx && versionCode === null) {
          if (dataType === 0x10 || dataType === 0x11) versionCode = data; // INT_DEC/INT_HEX
        } else if (nameIdx === vnIdx && versionName === null) {
          if (rawValue !== NO_STRING && rawValue < strings.length) versionName = strings[rawValue];
          else if (dataType === 0x03 && data < strings.length) versionName = strings[data];
        }
      }
      if (versionCode !== null && versionName !== null) break;
    }
    off += size;
  }
  if (versionCode === null && !versionName) return null;
  return { versionCode: versionCode || 0, versionName: versionName || '' };
}

/**
 * @param {Buffer} buf poore APK file ka content
 * @returns {{versionCode:number, versionName:string}|null} manifest se; fail ho null
 */
function apkVersionFromBuffer(buf) {
  try {
    if (!buf || buf.length < 200 || buf.readUInt32LE(0) !== 0x04034b50) return null; // "PK\u03\u04"
    const manifest = zipEntry(buf, MANIFEST);
    if (!manifest) return null;
    return axmlVersion(manifest);
  } catch (e) {
    return null;
  }
}

module.exports = { apkVersionFromBuffer };

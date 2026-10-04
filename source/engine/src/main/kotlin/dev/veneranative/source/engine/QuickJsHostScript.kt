package dev.veneranative.source.engine

/**
 * Compatibility JavaScript injected into every engine instance before the source script loads.
 *
 * The engine provides ECMAScript built-ins only — no `fetch`, `console`, `URL`, `TextEncoder`,
 * `atob`, timers or `crypto`. This script adds back exactly the surface real sources call, measured
 * against `venera-configs/manga_dex.js` (ADR-0007 §4.3):
 *
 * - `fetch(url, options)` with `ok` / `status` / `headers` / `text()` / `json()` / `arrayBuffer()`;
 * - `Convert.encodeUtf8` / `decodeUtf8`, the encoding helper the same source uses for form bodies;
 * - `Network.*` for the calls that are not `fetch`;
 * - cancellable `setTimeout` / `clearTimeout`, backed by a bounded timer Host API;
 * - `console.*`, routed to the host's log so source diagnostics are visible;
 * - `APP.locale` / `APP.version`.
 *
 * Deliberately **not** provided, because no source measured so far uses them and a hand-written
 * implementation would be guesswork: `URL`, `URLSearchParams`, `TextEncoder` / `TextDecoder`,
 * `atob` / `btoa`, `setInterval`, `crypto`, `structuredClone`, `Intl`. They are
 * listed in `docs/STATUS.md`; add one when a source needs it, with a test.
 */
internal object QuickJsHostScript {

    fun bootstrap(
        appLocale: String,
        appVersion: String,
        maxLogChars: Int,
    ): String = """
        (function () {
          "use strict";

          var hostCall = globalThis.__veneraHostCall;
          var logSink = globalThis.__veneraLog;
          try { delete globalThis.__veneraHostCall; } catch (_) {}
          try { delete globalThis.__veneraLog; } catch (_) {}

          if (typeof hostCall !== "function") {
            throw new Error("Host bridge is not installed.");
          }

          function hostFailure(message, code, retryable) {
            var error = new Error(message);
            error.code = code || "INTERNAL";
            error.retryable = Boolean(retryable);
            return error;
          }

          function callHost(method, payload) {
            var invocationId = globalThis.__veneraInvocationId;
            if (typeof invocationId !== "string" || invocationId.length === 0) {
              return Promise.reject(
                hostFailure("Host call requires an active invocation.", "INVALID_REQUEST", false)
              );
            }
            var encoded = JSON.stringify(payload === undefined || payload === null ? {} : payload);
            return Promise.resolve(hostCall(method, encoded, invocationId)).then(function (envelope) {
              var response = JSON.parse(envelope);
              if (response && response.ok) {
                return response.result;
              }
              var failure = response && response.error ? response.error : {};
              throw hostFailure(
                failure.message || "Host request failed.",
                failure.code,
                failure.retryable
              );
            });
          }

          var timerSequence = 0;
          var activeTimeouts = Object.create(null);
          globalThis.setTimeout = function (callback, delayMillis) {
            if (typeof callback !== "function") {
              throw new TypeError("setTimeout callback must be a function.");
            }
            var invocationId = globalThis.__veneraInvocationId;
            if (typeof invocationId !== "string" || invocationId.length === 0) {
              throw hostFailure("Timer requires an active invocation.", "INVALID_REQUEST", false);
            }
            var timerId = String(++timerSequence);
            var delay = Number(delayMillis);
            if (!Number.isFinite(delay) || delay < 0) delay = 0;
            delay = Math.min(Math.floor(delay), 120000);
            activeTimeouts[timerId] = invocationId;
            callHost("timer.sleep", { timerId: timerId, delayMillis: delay }).then(function () {
              if (activeTimeouts[timerId] !== invocationId) return;
              delete activeTimeouts[timerId];
              callback();
            }).catch(function () {
              delete activeTimeouts[timerId];
            });
            return timerId;
          };
          globalThis.clearTimeout = function (timerId) {
            timerId = String(timerId);
            var invocationId = activeTimeouts[timerId];
            if (invocationId === undefined) return;
            delete activeTimeouts[timerId];
            callHost("timer.cancel", { timerId: timerId }).catch(function () {});
          };
          globalThis.__veneraClearInvocationTimers = function (invocationId) {
            Object.keys(activeTimeouts).forEach(function (timerId) {
              if (activeTimeouts[timerId] !== invocationId) return;
              delete activeTimeouts[timerId];
              callHost("timer.cancel", { timerId: timerId }).catch(function () {});
            });
          };

          globalThis.veneraHost = Object.freeze({
            call: function (method, payload) {
              return callHost(String(method), payload);
            }
          });

          function utf8Encode(text) {
            var bytes = [];
            var index = 0;
            while (index < text.length) {
              var code = text.charCodeAt(index);
              index += 1;
              if (code >= 0xd800 && code <= 0xdbff && index < text.length) {
                var next = text.charCodeAt(index);
                if (next >= 0xdc00 && next <= 0xdfff) {
                  code = 0x10000 + ((code - 0xd800) << 10) + (next - 0xdc00);
                  index += 1;
                }
              }
              if (code >= 0xd800 && code <= 0xdfff) {
                code = 0xfffd;
              }
              if (code < 0x80) {
                bytes.push(code);
              } else if (code < 0x800) {
                bytes.push(0xc0 | (code >> 6), 0x80 | (code & 0x3f));
              } else if (code < 0x10000) {
                bytes.push(0xe0 | (code >> 12), 0x80 | ((code >> 6) & 0x3f), 0x80 | (code & 0x3f));
              } else {
                bytes.push(
                  0xf0 | (code >> 18),
                  0x80 | ((code >> 12) & 0x3f),
                  0x80 | ((code >> 6) & 0x3f),
                  0x80 | (code & 0x3f)
                );
              }
            }
            var encoded = new Uint8Array(bytes.length);
            for (var offset = 0; offset < bytes.length; offset += 1) {
              encoded[offset] = bytes[offset] & 0xff;
            }
            return encoded;
          }

          function utf8Decode(input) {
            var bytes;
            if (input instanceof Uint8Array) {
              bytes = input;
            } else if (input instanceof ArrayBuffer) {
              bytes = new Uint8Array(input);
            } else if (ArrayBuffer.isView(input)) {
              bytes = new Uint8Array(input.buffer, input.byteOffset, input.byteLength);
            } else {
              throw new TypeError("Convert.decodeUtf8 expects a byte array.");
            }
            var text = "";
            var index = 0;
            while (index < bytes.length) {
              var first = bytes[index];
              var code;
              var size;
              if (first < 0x80) {
                code = first;
                size = 1;
              } else if ((first & 0xe0) === 0xc0) {
                code = first & 0x1f;
                size = 2;
              } else if ((first & 0xf0) === 0xe0) {
                code = first & 0x0f;
                size = 3;
              } else if ((first & 0xf8) === 0xf0) {
                code = first & 0x07;
                size = 4;
              } else {
                code = -1;
                size = 1;
              }
              if (size > 1) {
                if (index + size > bytes.length) {
                  code = -1;
                } else {
                  for (var offset = 1; offset < size; offset += 1) {
                    var continuation = bytes[index + offset];
                    if ((continuation & 0xc0) !== 0x80) {
                      code = -1;
                      size = offset;
                      break;
                    }
                    code = (code << 6) | (continuation & 0x3f);
                  }
                }
              }
              index += size;
              if (code < 0 || code > 0x10ffff) {
                text += "\ufffd";
              } else if (code > 0xffff) {
                var adjusted = code - 0x10000;
                text += String.fromCharCode(0xd800 + (adjusted >> 10), 0xdc00 + (adjusted & 0x3ff));
              } else {
                text += String.fromCharCode(code);
              }
            }
            return text;
          }

          function bytesOf(input) {
            if (input instanceof Uint8Array) return Array.prototype.slice.call(input);
            if (input instanceof ArrayBuffer) return Array.prototype.slice.call(new Uint8Array(input));
            if (ArrayBuffer.isView(input)) {
              return Array.prototype.slice.call(new Uint8Array(input.buffer, input.byteOffset, input.byteLength));
            }
            throw new TypeError("Expected a byte array.");
          }

          var base64Alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
          function decodeBase64(value) {
            // JM's live domain configuration is UTF-8 text with a BOM before its Base64 payload.
            // The original source decoder accepts it; strip the BOM before validating alphabet.
            var text = String(value).replace(/^\uFEFF/, "").replace(/\s/g, "");
            if (text.length % 4 === 1 || /[^A-Za-z0-9+/=]/.test(text)) {
              throw new TypeError("Invalid base64 input.");
            }
            var output = [];
            var accumulator = 0;
            var bits = 0;
            for (var index = 0; index < text.length; index += 1) {
              var character = text.charAt(index);
              if (character === "=") break;
              accumulator = (accumulator << 6) | base64Alphabet.indexOf(character);
              bits += 6;
              if (bits >= 8) {
                bits -= 8;
                output.push((accumulator >> bits) & 0xff);
              }
            }
            return new Uint8Array(output);
          }

          function encodeBase64(input) {
            var bytes = bytesOf(input);
            var result = "";
            for (var index = 0; index < bytes.length; index += 3) {
              var first = bytes[index];
              var second = index + 1 < bytes.length ? bytes[index + 1] : 0;
              var third = index + 2 < bytes.length ? bytes[index + 2] : 0;
              var block = (first << 16) | (second << 8) | third;
              result += base64Alphabet.charAt((block >>> 18) & 63);
              result += base64Alphabet.charAt((block >>> 12) & 63);
              result += index + 1 < bytes.length ? base64Alphabet.charAt((block >>> 6) & 63) : "=";
              result += index + 2 < bytes.length ? base64Alphabet.charAt(block & 63) : "=";
            }
            return result;
          }

          // SHA-256 is kept in the JS compatibility layer so upstream's synchronous Convert.hmac
          // contract stays synchronous and never exposes Java objects to a source.
          var sha256Constants = [
            0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
            0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
            0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
            0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
            0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
            0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
            0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
            0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
          ];
          function rotateRight(value, count) { return (value >>> count) | (value << (32 - count)); }
          function sha256(input) {
            var bytes = bytesOf(input).slice();
            var bitLength = bytes.length * 8;
            bytes.push(0x80);
            while (bytes.length % 64 !== 56) bytes.push(0);
            var high = Math.floor(bitLength / 0x100000000);
            var low = bitLength >>> 0;
            bytes.push((high >>> 24) & 255, (high >>> 16) & 255, (high >>> 8) & 255, high & 255,
              (low >>> 24) & 255, (low >>> 16) & 255, (low >>> 8) & 255, low & 255);
            var hash = [0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19];
            for (var offset = 0; offset < bytes.length; offset += 64) {
              var words = new Array(64);
              for (var word = 0; word < 16; word += 1) {
                var at = offset + word * 4;
                words[word] = ((bytes[at] << 24) | (bytes[at + 1] << 16) | (bytes[at + 2] << 8) | bytes[at + 3]) >>> 0;
              }
              for (var next = 16; next < 64; next += 1) {
                var x = words[next - 15], y = words[next - 2];
                var s0 = rotateRight(x, 7) ^ rotateRight(x, 18) ^ (x >>> 3);
                var s1 = rotateRight(y, 17) ^ rotateRight(y, 19) ^ (y >>> 10);
                words[next] = (words[next - 16] + s0 + words[next - 7] + s1) >>> 0;
              }
              var a = hash[0], b = hash[1], c = hash[2], d = hash[3];
              var e = hash[4], f = hash[5], g = hash[6], h = hash[7];
              for (var round = 0; round < 64; round += 1) {
                var upper1 = rotateRight(e, 6) ^ rotateRight(e, 11) ^ rotateRight(e, 25);
                var choice = (e & f) ^ (~e & g);
                var temp1 = (h + upper1 + choice + sha256Constants[round] + words[round]) >>> 0;
                var upper0 = rotateRight(a, 2) ^ rotateRight(a, 13) ^ rotateRight(a, 22);
                var majority = (a & b) ^ (a & c) ^ (b & c);
                var temp2 = (upper0 + majority) >>> 0;
                h = g; g = f; f = e; e = (d + temp1) >>> 0;
                d = c; c = b; b = a; a = (temp1 + temp2) >>> 0;
              }
              hash[0] = (hash[0] + a) >>> 0; hash[1] = (hash[1] + b) >>> 0;
              hash[2] = (hash[2] + c) >>> 0; hash[3] = (hash[3] + d) >>> 0;
              hash[4] = (hash[4] + e) >>> 0; hash[5] = (hash[5] + f) >>> 0;
              hash[6] = (hash[6] + g) >>> 0; hash[7] = (hash[7] + h) >>> 0;
            }
            var output = new Uint8Array(32);
            for (var part = 0; part < hash.length; part += 1) {
              output[part * 4] = hash[part] >>> 24;
              output[part * 4 + 1] = hash[part] >>> 16;
              output[part * 4 + 2] = hash[part] >>> 8;
              output[part * 4 + 3] = hash[part];
            }
            return output;
          }
          function hmacSha256(keyInput, valueInput) {
            var key = bytesOf(keyInput);
            var value = bytesOf(valueInput);
            if (key.length > 64) key = bytesOf(sha256(key));
            while (key.length < 64) key.push(0);
            var inner = new Uint8Array(64 + value.length);
            var outer = new Uint8Array(96);
            for (var index = 0; index < 64; index += 1) {
              inner[index] = key[index] ^ 0x36;
              outer[index] = key[index] ^ 0x5c;
            }
            inner.set(value, 64);
            outer.set(sha256(inner), 64);
            return sha256(outer);
          }
          function hexEncode(input) {
            return bytesOf(input).map(function (byte) { return byte.toString(16).padStart(2, "0"); }).join("");
          }

          // Keep the synchronous Convert crypto helpers inside the JS sandbox. Source scripts
          // such as JM use them while constructing auth headers and decrypting discovery data.
          function md5(input) {
            var bytes = bytesOf(input).slice();
            var bitLength = bytes.length * 8;
            bytes.push(0x80);
            while (bytes.length % 64 !== 56) bytes.push(0);
            var lowLength = bitLength >>> 0;
            var highLength = Math.floor(bitLength / 0x100000000) >>> 0;
            bytes.push(lowLength & 255, (lowLength >>> 8) & 255, (lowLength >>> 16) & 255, (lowLength >>> 24) & 255,
              highLength & 255, (highLength >>> 8) & 255, (highLength >>> 16) & 255, (highLength >>> 24) & 255);
            var shifts = [7, 12, 17, 22, 5, 9, 14, 20, 4, 11, 16, 23, 6, 10, 15, 21];
            var constants = new Array(64);
            for (var ci = 0; ci < 64; ci += 1) constants[ci] = Math.floor(Math.abs(Math.sin(ci + 1)) * 0x100000000) >>> 0;
            var a0 = 0x67452301, b0 = 0xefcdab89, c0 = 0x98badcfe, d0 = 0x10325476;
            for (var offset = 0; offset < bytes.length; offset += 64) {
              var words = new Array(16);
              for (var wi = 0; wi < 16; wi += 1) {
                var at = offset + wi * 4;
                words[wi] = (bytes[at] | (bytes[at + 1] << 8) | (bytes[at + 2] << 16) | (bytes[at + 3] << 24)) >>> 0;
              }
              var a = a0, b = b0, c = c0, d = d0;
              for (var i = 0; i < 64; i += 1) {
                var f, g, round = i >>> 4;
                if (round === 0) { f = (b & c) | (~b & d); g = i; }
                else if (round === 1) { f = (d & b) | (~d & c); g = (5 * i + 1) & 15; }
                else if (round === 2) { f = b ^ c ^ d; g = (3 * i + 5) & 15; }
                else { f = c ^ (b | ~d); g = (7 * i) & 15; }
                var shift = shifts[round * 4 + (i & 3)];
                var sum = (a + f + constants[i] + words[g]) >>> 0;
                var rotated = ((sum << shift) | (sum >>> (32 - shift))) >>> 0;
                var previousD = d;
                d = c; c = b; b = (b + rotated) >>> 0; a = previousD;
              }
              a0 = (a0 + a) >>> 0; b0 = (b0 + b) >>> 0;
              c0 = (c0 + c) >>> 0; d0 = (d0 + d) >>> 0;
            }
            var digest = new Uint8Array(16), state = [a0, b0, c0, d0];
            for (var si = 0; si < 4; si += 1) {
              digest[si * 4] = state[si] & 255;
              digest[si * 4 + 1] = (state[si] >>> 8) & 255;
              digest[si * 4 + 2] = (state[si] >>> 16) & 255;
              digest[si * 4 + 3] = (state[si] >>> 24) & 255;
            }
            return digest;
          }

          var aesSbox = [
            99,124,119,123,242,107,111,197,48,1,103,43,254,215,171,118,202,130,201,125,250,89,71,240,173,212,162,175,156,164,114,192,
            183,253,147,38,54,63,247,204,52,165,229,241,113,216,49,21,4,199,35,195,24,150,5,154,7,18,128,226,235,39,178,117,
            9,131,44,26,27,110,90,160,82,59,214,179,41,227,47,132,83,209,0,237,32,252,177,91,106,203,190,57,74,76,88,207,208,239,
            170,251,67,77,51,133,69,249,2,127,80,60,159,168,81,163,64,143,146,157,56,245,188,182,218,33,16,255,243,210,205,12,
            19,236,95,151,68,23,196,167,126,61,100,93,25,115,96,129,79,220,34,42,144,136,70,238,184,20,222,94,11,219,224,50,
            58,10,73,6,36,92,194,211,172,98,145,149,228,121,231,200,55,109,141,213,78,169,108,86,244,234,101,122,174,8,186,120,
            37,46,28,166,180,198,232,221,116,31,75,189,139,138,112,62,181,102,72,3,246,14,97,53,87,185,134,193,29,158,225,248,
            152,17,105,217,142,148,155,30,135,233,206,85,40,223,140,161,137,13,191,230,66,104,65,153,45,15,176,84,187,22
          ];
          var aesInvSbox = new Array(256);
          for (var ai = 0; ai < 256; ai += 1) aesInvSbox[aesSbox[ai]] = ai;
          function aesMultiply(a, b) {
            var result = 0;
            while (b) {
              if (b & 1) result ^= a;
              a = ((a << 1) ^ ((a & 0x80) ? 0x11b : 0)) & 255;
              b >>>= 1;
            }
            return result;
          }
          function aesExpandKey(key) {
            var nk = key.length / 4, rounds = nk + 6, words = new Array(4 * (rounds + 1));
            if (key.length !== 16 && key.length !== 24 && key.length !== 32) throw new TypeError("AES key must be 16, 24, or 32 bytes.");
            for (var i = 0; i < nk; i += 1) words[i] = key.slice(i * 4, i * 4 + 4);
            var rcon = 1;
            for (var wi = nk; wi < words.length; wi += 1) {
              var temp = words[wi - 1].slice();
              if (wi % nk === 0) {
                temp.push(temp.shift());
                for (var ti = 0; ti < 4; ti += 1) temp[ti] = aesSbox[temp[ti]];
                temp[0] ^= rcon;
                rcon = aesMultiply(rcon, 2);
              } else if (nk > 6 && wi % nk === 4) {
                for (var tj = 0; tj < 4; tj += 1) temp[tj] = aesSbox[temp[tj]];
              }
              words[wi] = [
                words[wi - nk][0] ^ temp[0], words[wi - nk][1] ^ temp[1],
                words[wi - nk][2] ^ temp[2], words[wi - nk][3] ^ temp[3]
              ];
            }
            return { words: words, rounds: rounds };
          }
          function aesDecryptBlock(block, expanded) {
            var state = block.slice(), words = expanded.words, rounds = expanded.rounds;
            function addKey(round) {
              for (var col = 0; col < 4; col += 1) for (var row = 0; row < 4; row += 1) {
                state[col * 4 + row] ^= words[round * 4 + col][row];
              }
            }
            function inverseShiftRows() {
              for (var row = 1; row < 4; row += 1) {
                var saved = [];
                for (var col = 0; col < 4; col += 1) saved[col] = state[col * 4 + row];
                for (var dst = 0; dst < 4; dst += 1) state[dst * 4 + row] = saved[(dst - row + 4) & 3];
              }
            }
            addKey(rounds);
            for (var round = rounds - 1; round > 0; round -= 1) {
              inverseShiftRows();
              for (var si = 0; si < 16; si += 1) state[si] = aesInvSbox[state[si]];
              addKey(round);
              for (var col = 0; col < 4; col += 1) {
                var at = col * 4, a = state[at], b = state[at + 1], c = state[at + 2], d = state[at + 3];
                state[at] = aesMultiply(a, 14) ^ aesMultiply(b, 11) ^ aesMultiply(c, 13) ^ aesMultiply(d, 9);
                state[at + 1] = aesMultiply(a, 9) ^ aesMultiply(b, 14) ^ aesMultiply(c, 11) ^ aesMultiply(d, 13);
                state[at + 2] = aesMultiply(a, 13) ^ aesMultiply(b, 9) ^ aesMultiply(c, 14) ^ aesMultiply(d, 11);
                state[at + 3] = aesMultiply(a, 11) ^ aesMultiply(b, 13) ^ aesMultiply(c, 9) ^ aesMultiply(d, 14);
              }
            }
            inverseShiftRows();
            for (var last = 0; last < 16; last += 1) state[last] = aesInvSbox[state[last]];
            addKey(0);
            return state;
          }
          function decryptAesEcb(ciphertextInput, keyInput) {
            var ciphertext = bytesOf(ciphertextInput), key = bytesOf(keyInput);
            if (ciphertext.length === 0 || ciphertext.length % 16 !== 0) throw new TypeError("AES-ECB data must contain complete blocks.");
            var expanded = aesExpandKey(key), plaintext = [];
            for (var offset = 0; offset < ciphertext.length; offset += 16) {
              var block = aesDecryptBlock(ciphertext.slice(offset, offset + 16), expanded);
              for (var bi = 0; bi < 16; bi += 1) plaintext.push(block[bi]);
            }
            var padding = plaintext[plaintext.length - 1];
            if (padding > 0 && padding <= 16) {
              var validPadding = true;
              for (var pi = plaintext.length - padding; pi < plaintext.length; pi += 1) {
                if (plaintext[pi] !== padding) { validPadding = false; break; }
              }
              if (validPadding) plaintext.length -= padding;
            }
            return new Uint8Array(plaintext);
          }

          globalThis.Convert = Object.freeze({
            encodeUtf8: function (text) {
              return utf8Encode(String(text === undefined || text === null ? "" : text));
            },
            decodeUtf8: function (bytes) {
              return utf8Decode(bytes);
            },
            encodeBase64: encodeBase64,
            decodeBase64: decodeBase64,
            md5: md5,
            decryptAesEcb: decryptAesEcb,
            hmac: function (key, value, hash) {
              if (String(hash).toLowerCase() !== "sha256") throw new TypeError("Unsupported HMAC algorithm.");
              return hmacSha256(key, value).buffer;
            },
            hmacString: function (key, value, hash) {
              return hexEncode(Convert.hmac(key, value, hash));
            },
            hexEncode: hexEncode
          });

          globalThis.randomInt = function (min, max) {
            min = Math.ceil(Number(min));
            max = Math.floor(Number(max));
            if (!Number.isFinite(min) || !Number.isFinite(max) || max < min) {
              throw new RangeError("Invalid random integer range.");
            }
            return min + Math.floor(Math.random() * (max - min + 1));
          };

          function describe(value) {
            try {
              if (typeof value === "string") {
                return value;
              }
              if (value instanceof Error) {
                return value.stack || value.name + ": " + value.message;
              }
              if (typeof value === "function") {
                return "[function " + (value.name || "anonymous") + "]";
              }
              var encoded = JSON.stringify(value);
              return encoded === undefined ? String(value) : encoded;
            } catch (_) {
              return String(value);
            }
          }

          function writeLog(level, args) {
            if (typeof logSink !== "function") {
              return;
            }
            try {
              var parts = [];
              for (var index = 0; index < args.length; index += 1) {
                parts.push(describe(args[index]));
              }
              var message = parts.join(" ");
              if (message.length > $maxLogChars) {
                message = message.slice(0, $maxLogChars) + "...";
              }
              var pending = logSink(level, message);
              if (pending && typeof pending.then === "function") {
                pending.then(undefined, function () {});
              }
            } catch (_) {
              // Diagnostics must never break a source.
            }
          }

          var consoleObject = {};
          ["log", "info", "warn", "error", "debug"].forEach(function (level) {
            consoleObject[level] = function () {
              writeLog(level, Array.prototype.slice.call(arguments));
            };
          });
          globalThis.console = Object.freeze(consoleObject);

          function headerObject(headers) {
            var result = {};
            if (headers === undefined || headers === null) {
              return result;
            }
            if (Array.isArray(headers)) {
              headers.forEach(function (pair) {
                if (pair && pair.length >= 2) {
                  result[String(pair[0])] = String(pair[1]);
                }
              });
              return result;
            }
            Object.keys(headers).forEach(function (name) {
              var value = headers[name];
              result[String(name)] = value === undefined || value === null ? "" : String(value);
            });
            return result;
          }

          function bodyToText(body) {
            if (body === undefined || body === null) {
              return null;
            }
            if (typeof body === "string") {
              return body;
            }
            if (body instanceof Uint8Array || body instanceof ArrayBuffer || ArrayBuffer.isView(body)) {
              // The transport carries text: the body is decoded back into the string it encoded.
              // True binary bodies need a byte channel in the Host API, which Stage 1 does not have.
              return utf8Decode(body);
            }
            // Source scripts commonly pass a plain object to Network.post for JSON APIs (for
            // example, GraphQL sources). String(object) would send "[object Object]" and make
            // those requests fail before the source can parse a response.
            if (typeof body === "object") {
              return JSON.stringify(body);
            }
            return String(body);
          }

          function decodeResponse(payload) {
            var rawHeaders = payload && payload.headers ? payload.headers : {};
            var headers = {};
            Object.keys(rawHeaders).forEach(function (name) {
              var value = rawHeaders[name];
              headers[name] = Array.isArray(value) ? value.map(String) : [String(value)];
            });
            var status = payload && typeof payload.statusCode === "number" ? payload.statusCode : 0;
            return {
              status: status,
              headers: headers,
              body: payload && payload.body !== undefined && payload.body !== null
                ? String(payload.body)
                : ""
            };
          }

          function request(method, url, headers, body) {
            if (typeof url !== "string" || url.length === 0) {
              return Promise.reject(
                hostFailure("Request URL must not be empty.", "INVALID_REQUEST", false)
              );
            }
            // Source initializers may fire-and-forget optional endpoint discovery requests. A
            // transient network outage during installation must not make an otherwise valid
            // script impossible to add; return an unsuccessful response for those install-time
            // requests. Requests made after installation retain the normal rejecting fetch/network
            // semantics so product operations can report and retry genuine network failures.
            var invocationId = globalThis.__veneraInvocationId;
            return callHost("http.request", {
              url: url,
              method: method,
              headers: headerObject(headers),
              body: bodyToText(body)
            }).then(decodeResponse).catch(function (failure) {
              if (invocationId === "source-install") {
                return { status: 0, headers: {}, body: "" };
              }
              throw failure;
            });
          }

          function jmApiFailoverUrls(url) {
            var source = globalThis.ComicSource && globalThis.ComicSource.sources
              ? globalThis.ComicSource.sources.jm
              : null;
            var sourceClass = source && source.constructor;
            var domains = sourceClass && Array.isArray(sourceClass.apiDomains)
              ? sourceClass.apiDomains
              : sourceClass && Array.isArray(sourceClass.fallbackServers)
                ? sourceClass.fallbackServers
                : [];
            var match = /^https:\/\/([^/?#]+)/i.exec(url);
            if (!match || domains.length < 2) return [];
            var host = match[1].toLowerCase();
            var candidates = [];
            domains.slice(0, 8).forEach(function (value) {
              var candidate = String(value || "").trim().replace(/^https?:\/\//i, "").replace(/\/$/, "");
              if (!/^[a-z0-9.-]+(?::[0-9]{1,5})?$/i.test(candidate)) return;
              if (candidate.toLowerCase() !== host && candidates.indexOf(candidate) < 0) {
                candidates.push(candidate);
              }
            });
            return candidates.map(function (candidate) {
              return url.replace(/^https:\/\/[^/?#]+/i, "https://" + candidate);
            });
          }

          function requestJmWithFailover(url, headers) {
            var alternatives = jmApiFailoverUrls(url);
            if (alternatives.length === 0) return request("GET", url, headers, null);

            function retry(index, lastResponse) {
              if (index >= alternatives.length) return Promise.resolve(lastResponse);
              return request("GET", alternatives[index], headers, null).then(function (response) {
                if (response.status === 404 || response.status === 0) {
                  return retry(index + 1, response);
                }
                return response;
              }).catch(function (failure) {
                if (failure && (failure.code === "NETWORK_CONNECTION" || failure.code === "NETWORK_TIMEOUT")) {
                  return retry(index + 1, lastResponse);
                }
                throw failure;
              });
            }

            return request("GET", url, headers, null).then(function (response) {
              if (response.status === 404 || response.status === 0) {
                return retry(0, response);
              }
              return response;
            }).catch(function (failure) {
              if (failure && (failure.code === "NETWORK_CONNECTION" || failure.code === "NETWORK_TIMEOUT")) {
                return retry(0, null);
              }
              throw failure;
            });
          }

          function headerLookup(raw) {
            var lookup = {};
            Object.keys(raw).forEach(function (name) {
              lookup[name.toLowerCase()] = raw[name];
            });
            return {
              get: function (name) {
                var values = lookup[String(name).toLowerCase()];
                return values && values.length > 0 ? values[0] : null;
              },
              has: function (name) {
                return String(name).toLowerCase() in lookup;
              },
              forEach: function (callback) {
                Object.keys(lookup).forEach(function (name) {
                  callback(lookup[name].join(", "), name);
                });
              }
            };
          }

          function toResponse(raw, url) {
            var text = raw.body;
            return {
              status: raw.status,
              ok: raw.status >= 200 && raw.status < 300,
              url: url,
              headers: headerLookup(raw.headers),
              text: function () {
                return Promise.resolve(text);
              },
              json: function () {
                return new Promise(function (resolve) {
                  resolve(JSON.parse(text));
                });
              },
              arrayBuffer: function () {
                return Promise.resolve(utf8Encode(text).buffer);
              }
            };
          }

          globalThis.fetch = function (url, options) {
            var init = options || {};
            var method = String(init.method || "GET").toUpperCase();
            var target = String(url);
            return request(method, target, init.headers, init.body).then(function (raw) {
              return toResponse(raw, target);
            });
          };

          globalThis.Network = Object.freeze({
            get: function (url, headers) {
              var target = String(url);
              return jmApiFailoverUrls(target).length > 0
                ? requestJmWithFailover(target, headers)
                : request("GET", target, headers, null);
            },
            post: function (url, headers, data) {
              return request("POST", String(url), headers, data);
            },
            put: function (url, headers, data) {
              return request("PUT", String(url), headers, data);
            },
            patch: function (url, headers, data) {
              return request("PATCH", String(url), headers, data);
            },
            delete: function (url, headers) {
              return request("DELETE", String(url), headers, null);
            },
            fetchBytes: function (method, url, headers, data) {
              return request(String(method).toUpperCase(), String(url), headers, data)
                .then(function (raw) {
                  return utf8Encode(raw.body).buffer;
                });
            }
          });

          globalThis.APP = Object.freeze({
            locale: ${jsQuote(appLocale)},
            version: ${jsQuote(appVersion)}
          });
        })();
    """.trimIndent() + "\n\n" + SourceBaseScript.script + "\n\n" + SourceProbeScript.script
}

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
 * - `console.*`, routed to the host's log so source diagnostics are visible;
 * - `APP.locale` / `APP.version`.
 *
 * Deliberately **not** provided, because no source measured so far uses them and a hand-written
 * implementation would be guesswork: `URL`, `URLSearchParams`, `TextEncoder` / `TextDecoder`,
 * `atob` / `btoa`, `setTimeout` / `setInterval`, `crypto`, `structuredClone`, `Intl`. They are
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

          globalThis.Convert = Object.freeze({
            encodeUtf8: function (text) {
              return utf8Encode(String(text === undefined || text === null ? "" : text));
            },
            decodeUtf8: function (bytes) {
              return utf8Decode(bytes);
            }
          });

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
            return callHost("http.request", {
              url: url,
              method: method,
              headers: headerObject(headers),
              body: bodyToText(body)
            }).then(decodeResponse);
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
              return request("GET", String(url), headers, null);
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
            locale: ${quote(appLocale)},
            version: ${quote(appVersion)}
          });
        })();
    """.trimIndent()

    /** JavaScript string literal escaping: the values come from the host, not from the script. */
    private fun quote(value: String): String = buildString(value.length + 2) {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (character.code < 0x20) {
                        append("\\u")
                        append(character.code.toString(16).padStart(4, '0'))
                    } else {
                        append(character)
                    }
                }
            }
        }
        append('"')
    }
}

package dev.veneranative.source.engine

/**
 * Lets the host read what a source declares without calling it.
 *
 * The contract needs to know whether a source implements `search`, which explore pages it declares
 * and what filters it offers — all of that is data on the source object, and reading it must not
 * require the caller to know the protocol (ADR-0007 §2.1 keeps capability discovery separate from
 * invocation).
 *
 * `__venera.probe(pathJson)` walks the loaded instance along `path` and answers with a
 * **description** of what it finds: primitives stay themselves, functions become the string
 * `"function"`, arrays and objects become `{type, length|keyCount, items|entries}` with depth, item,
 * key and string lengths capped so a source with large data blocks cannot flood the host.
 *
 * The host interprets those names; the engine only reports shapes.
 */
internal object SourceProbeScript {

    val script: String =
        """
        (function () {
          "use strict";

          var MAX_DEPTH = 4;
          var MAX_ITEMS = 64;
          var MAX_KEYS = 64;
          var MAX_STRING = 512;

          function describeValue(value, depth) {
            if (value === null || value === undefined) {
              return null;
            }
            var kind = typeof value;
            if (kind === "function") {
              return "function";
            }
            if (kind === "string") {
              return value.length > MAX_STRING ? value.slice(0, MAX_STRING) : value;
            }
            if (kind === "number" || kind === "boolean") {
              return value;
            }
            if (depth >= MAX_DEPTH) {
              return kind;
            }
            if (Object.prototype.toString.call(value) === "[object Array]") {
              var items = [];
              var itemCount = Math.min(value.length, MAX_ITEMS);
              for (var index = 0; index < itemCount; index += 1) {
                items.push(describeValue(value[index], depth + 1));
              }
              return { type: "array", length: value.length, items: items };
            }
            if (kind === "object") {
              var keys = Object.keys(value);
              var entries = {};
              var keyCount = Math.min(keys.length, MAX_KEYS);
              for (var position = 0; position < keyCount; position += 1) {
                entries[keys[position]] = describeValue(value[keys[position]], depth + 1);
              }
              return { type: "object", keyCount: keys.length, entries: entries };
            }
            return kind;
          }

          globalThis.__venera = Object.freeze({
            probe: function (pathJson) {
              var source = globalThis.__veneraSource;
              if (source === null || source === undefined) {
                throw new Error("No source is loaded.");
              }
              var path = JSON.parse(pathJson);
              if (!Array.isArray(path)) {
                throw new Error("Probe path must be a JSON array.");
              }
              var target = source;
              for (var index = 0; index < path.length; index += 1) {
                if (target === null || target === undefined) {
                  return JSON.stringify(null);
                }
                target = target[path[index]];
              }
              return JSON.stringify(describeValue(target, 0));
            }
          });
        })();
        """.trimIndent()
}

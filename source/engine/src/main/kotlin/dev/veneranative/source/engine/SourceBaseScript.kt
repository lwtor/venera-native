package dev.veneranative.source.engine

/**
 * The JavaScript `ComicSource` base class every source extends.
 *
 * Upstream ships this in `assets/init.js`; sources rely on it for their identity fields and for the
 * data/setting helpers. Two honest Stage 1 limitations are baked in and must not be mistaken for
 * the real thing (ADR-0007 §2.1 keeps source data, settings and accounts out of Stage 1):
 *
 * - the data store lives for as long as the engine instance, so `saveData` does not survive a
 *   reload;
 * - `loadSetting` answers with the value the source declared in its own `settings` block instead of
 *   a user-chosen one, and `isLogged` is always false.
 *
 * Everything else follows upstream verbatim, including `translate`'s locale lookup and the
 * `sources` registry the host reaches member calls through.
 */
internal object SourceBaseScript {

    val script: String =
        """
        (function () {
          "use strict";

          var data = {};

          class ComicSource {
            constructor() {
              this.name = "";
              this.key = "";
              this.version = "";
              this.minAppVersion = "";
              this.url = "";
              this.translation = {};
            }

            init() {}

            loadData(dataKey) {
              return Object.prototype.hasOwnProperty.call(data, dataKey) ? data[dataKey] : null;
            }

            saveData(dataKey, value) {
              data[dataKey] = value;
            }

            deleteData(dataKey) {
              delete data[dataKey];
            }

            loadSetting(key) {
              var settings = this.settings;
              if (settings && typeof settings === "object") {
                var declared = settings[key];
                if (declared && typeof declared === "object" && declared.default !== undefined) {
                  return declared.default;
                }
              }
              return null;
            }

            get isLogged() {
              return false;
            }

            translate(key) {
              var app = globalThis.APP;
              var locale = app && typeof app.locale === "string" ? app.locale : "";
              var table = this.translation ? this.translation[locale] : undefined;
              var value = table ? table[key] : undefined;
              return value === undefined || value === null ? key : value;
            }
          }

          ComicSource.sources = {};
          globalThis.ComicSource = ComicSource;
        })();
        """.trimIndent()
}

package dev.veneranative.source.api.protocol

/** Restricted compatibility operation: one comic ID, one fixed read-only metadata endpoint. */
object CopyMangaMetadataAdapter {
    const val MEMBER = "__venera.copyMangaTags"
    const val BATCH_MEMBER = "__venera.copyMangaTagsBatch"

    val batchFunctionExpression = """
        async function (ids) {
          var source = globalThis.__veneraSource;
          if (!source || source.key !== "copy_manga" || !Array.isArray(ids) || ids.length < 1 || ids.length > 6 ||
              ids.some(function(id) { return typeof id !== "string" || !id.trim(); }) ||
              typeof source.apiUrl !== "string" || typeof source.getReqID !== "function") {
            throw new Error("CopyManga tag metadata is unavailable.");
          }
          var requestId = await source.getReqID();
          var result = Object.create(null), next = 0;
          async function worker() {
            while (next < ids.length) {
              var id = ids[next++];
              try {
                var url = source.apiUrl.replace(/\/$/, "") + "/api/v3/comic2/" + encodeURIComponent(id) +
                  "?in_mainland=true&request_id=" + encodeURIComponent(requestId || "") + "&platform=3";
                var response = await Network.get(url, source.headers);
                if (response.status !== 200) continue;
                var data = JSON.parse(response.body);
                var comic = data && data.results && data.results.comic;
                if (comic && Array.isArray(comic.theme)) {
                  result[id] = comic.theme.map(function(item) { return item && item.name; })
                    .filter(function(name) { return typeof name === "string" && name.trim(); });
                }
              } catch (_) { /* Optional labels never prevent usable list results. */ }
            }
          }
          await Promise.all([worker(), worker(), worker()]);
          return result;
        }
    """.trimIndent()

    val functionExpression = """
        async function (id) {
          var source = globalThis.__veneraSource;
          if (!source || source.key !== "copy_manga" || typeof id !== "string" || !id.trim() ||
              typeof source.apiUrl !== "string" || typeof source.getReqID !== "function") {
            throw new Error("CopyManga tag metadata is unavailable.");
          }
          var requestId = await source.getReqID();
          var url = source.apiUrl.replace(/\/$/, "") + "/api/v3/comic2/" + encodeURIComponent(id) +
            "?in_mainland=true&request_id=" + encodeURIComponent(requestId || "") + "&platform=3";
          var response = await Network.get(url, source.headers);
          if (response.status !== 200) throw new Error("Comic tag request failed.");
          var data = JSON.parse(response.body);
          var comic = data && data.results && data.results.comic;
          if (!comic || !Array.isArray(comic.theme)) throw new Error("Invalid comic tag metadata.");
          return comic.theme.map(function (item) { return item && item.name; })
            .filter(function (name) { return typeof name === "string" && name.trim(); });
        }
    """.trimIndent()
}

package dev.veneranative.source.api.protocol

/** Restricted compatibility operation: one comic ID, one fixed read-only metadata endpoint. */
object CopyMangaMetadataAdapter {
    const val MEMBER = "__venera.copyMangaTags"

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

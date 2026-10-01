// D02-only source for repeatable download pause/resume and retry checks.
class DownloadControlSource extends ComicSource {
  constructor() {
    super();
    this.name = "D02 Download Fixture";
    this.key = "download_control_source";
    this.version = "1.0.0";
    this.url = "http://127.0.0.1:8765";

    const title = (id) => id === "slow" ? "D02 Slow Comic" : "D02 Retry Comic";
    const comic = (id) => ({
      id: id,
      title: title(id),
      cover: this.url + "/page_normal_1080x1440.jpg",
      tags: ["D02"]
    });

    this.explore = [{
      title: "Download checks",
      type: "multiPageComicList",
      load: async () => ({ comics: [comic("slow"), comic("retry")], maxPage: 1 })
    }];
    this.comic = {
      loadInfo: async (id) => ({
        title: title(id),
        description: "Repository-owned download verification fixture.",
        cover: this.url + "/page_normal_1080x1440.jpg",
        chapters: { ch1: "Chapter 1" }
      }),
      loadEp: async (id) => ({ images: id === "slow" ? [
        this.url + "/slow/page_normal_1080x1440.jpg",
        this.url + "/slow/page_long_1080x6000.png",
        this.url + "/slow/page_wide_1920x1080.png"
      ] : [
        this.url + "/page_normal_1080x1440.jpg",
        this.url + "/page_long_1080x6000.png",
        this.url + "/retry/page_wide_1920x1080.png"
      ] })
    };
  }
}

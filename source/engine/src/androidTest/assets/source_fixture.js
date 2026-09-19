let fixtureCounter = 0;

function add(left, right) {
  return { sum: left + right };
}

function echo(value) {
  return value;
}

function increment() {
  fixtureCounter += 1;
  return fixtureCounter;
}

function fail() {
  throw new Error("fixture failure");
}

function hang() {
  while (true) {
    // Deliberately non-terminating. Timeout and cancellation must replace this isolate.
  }
}

async function hostGet(url) {
  return veneraHost.call("http.request", {
    url: url,
    method: "GET",
    headers: { "X-Fixture": "engine" }
  });
}

async function hostPost(url, body) {
  return veneraHost.call("http.request", {
    url: url,
    method: "POST",
    headers: { "Content-Type": "text/plain; charset=utf-8" },
    body: body
  });
}

async function forbiddenHostCall() {
  return veneraHost.call("files.read", { path: "blocked" });
}

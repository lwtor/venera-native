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

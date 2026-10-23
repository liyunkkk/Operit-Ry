const assert = require("node:assert/strict");
const test = require("node:test");
const vm = require("node:vm");
const fs = require("node:fs");
const path = require("node:path");

function harness() {
  const updates = [];
  let result;
  const sandbox = {
    exports: {},
    complete: value => { result = value; },
    Tools: { SoftwareSettings: {
      getFunctionModelConfig: async () => ({
        configId: "model", config: { maxContextLength: 128, enableSummary: true },
      }),
      updateModelConfig: async (id, fields) => {
        updates.push({ id, fields });
        return { config: { maxContextLength: fields.max_context_length } };
      },
    } },
  };
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, "../operit_editor.js"), "utf8"), sandbox);
  return { api: sandbox.exports, updates, result: () => result };
}

test("capacity setter requires an explicit value and rejects retired conversation fields", async () => {
  for (const params of [{}, { max_context_length: 0 }, { max_context_length: Infinity },
    { max_context_length: 500, enable_summary: false }]) {
    const h = harness();
    await h.api.set_context_summary_config(params);
    assert.equal(h.result().success, false);
    assert.equal(h.updates.length, 0);
  }
});

test("capacity setter updates only the selected model capacity", async () => {
  const h = harness();
  await h.api.set_context_summary_config({ max_context_length: 500 });
  assert.equal(h.result().success, true);
  assert.equal(JSON.stringify(h.updates), JSON.stringify([{ id: "model", fields: { max_context_length: 500 } }]));
});

test("capacity getter does not present legacy fields as current conversation settings", async () => {
  const h = harness();
  await h.api.get_context_summary_config();
  assert.equal(h.result().success, true);
  assert.equal(JSON.stringify(h.result().data.context_summary), JSON.stringify({ max_context_length: 128 }));
});

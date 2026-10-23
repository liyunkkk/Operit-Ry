const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const assert = require("node:assert/strict");
const root = path.resolve(__dirname, "../..");
const source = fs.readFileSync(path.join(root, "examples/dynamic_ui.js"), "utf8");
let result, checked, liveFlag;
let report = {valid:true,reference:"demo",message:"```a2ui-ref\ndemo\n```"};
const sandbox = {
    exports: {}, __operitCurrentCallId:"host-call", complete:value => { result = value; },
    NativeInterface: {
        dynamicUiGuide:() => "NATIVE SIGNATURES",
        dynamicUiIcons:(query,limit,offset) => {
            assert.equal(query,"orbit");
            assert.equal(limit,30);
            assert.equal(offset,0);
            return JSON.stringify({icons:["lucide:orbit"],total:1,next_offset:null});
        },
        updateDynamicUi:(callId,reference,changes,remove) => {
            assert.equal(callId,"host-call");
            assert.equal(reference,"demo");
            assert.equal(changes,'title = Text(text="Revised")');
            assert.deepEqual(JSON.parse(remove),["old"]);
            return JSON.stringify({valid:true,reference:"revision",baseReference:reference});
        },
        prepareDynamicUi:(callId, source, live) => {
            assert.equal(callId, "host-call");
            checked = source;
            liveFlag = live;
            return JSON.stringify(report);
        },
        patchDynamicUiData:(callId, reference, version, data) => {
            assert.equal(callId, "host-call");
            assert.equal(reference, "demo");
            assert.equal(version, 0);
            assert.deepEqual(JSON.parse(data), {progress:50});
            return JSON.stringify({valid:true,version:1});
        },
    },
};
vm.runInNewContext(source, sandbox);
(async () => {
    await sandbox.exports.read_guide();
    assert.ok(result.message.endsWith("NATIVE SIGNATURES"));
    await sandbox.exports.list_icons({query:"orbit"});
    assert.deepEqual(Array.from(result.icons),["lucide:orbit"]);
    await sandbox.exports.list_icons({query:"orbit",limit:0});
    assert.equal(result.valid,false);
    await sandbox.exports.prepare_html({label:"Visual",html:'<div class="x">Hi</div>',css:".x{color:red}"});
    assert.ok(checked.startsWith('root=Html('));
    assert.ok(checked.includes('maxHeight=720'));
    await sandbox.exports.prepare_html({label:"Visual",html:"<p>x</p>",max_height:999});
    assert.equal(result.valid,false);
    for (const name of ["shopping_list","trip_budget","filtered_chart","calculator","cost_calculator","charts","form","info","stats","progress","responsive","data","vector","repeat","curves","interactive_flow","p2_controls","p2_lists","flow_steps","flowchart","data_story","mixed_branch","profile","backgrounds","surfaces"]) {
        await sandbox.exports.read_example({name});
        assert.equal(result.success, true);
        assert.equal(result.valid, true);
        assert.ok(result.message.includes("a2ui-ref"));
        assert.ok(!result.message.includes("root ="), "Do not ask the model to copy source again");
        const file = path.join(root, "app/src/test/resources/dynamicui", name + ".express");
        if (process.argv.includes("--update")) fs.writeFileSync(file, checked);
        else assert.equal(fs.readFileSync(file, "utf8"), checked);
        const compiledSource = checked;
        checked = undefined;
        await sandbox.exports.read_example({name,source_only:true});
        assert.equal(checked, undefined, "Inspecting source must not create a card");
        assert.equal(result.source, compiledSource);
        assert.equal(result.reference, undefined);
    }
    await sandbox.exports.read_example({name:"shopping_list",source_only:"true"});
    assert.equal(result.valid,false);
    assert.equal(checked,undefined);
    await sandbox.exports.prepare_card({source:'root = Text(text="hello")'});
    assert.equal(checked, 'root = Text(text="hello")');
    assert.equal(liveFlag, false);
    await sandbox.exports.prepare_card({source:'root = Text(text="hello")',live:true});
    assert.equal(liveFlag, true);
    await sandbox.exports.patch_card_data({reference:"demo",expected_version:0,data:{progress:50}});
    assert.equal(result.version, 1);
    report = {valid:false,code:"invalid_express",line:3,column:5,message:"Expected ')'."};
    await sandbox.exports.prepare_card({source:'root = Text('});
    assert.equal(result.success, true);
    assert.equal(result.valid, false);
    assert.equal(result.line, 3);
    assert.equal(result.column, 5);
    assert.equal(result.code, "invalid_express");
    await sandbox.exports.prepare_card({source:42});
    assert.equal(result.code, "invalid_source");
    await sandbox.exports.update_card({reference:"demo",changes:'title = Text(text="Revised")',remove:["old"]});
    assert.equal(result.reference,"revision");
    await sandbox.exports.update_card({reference:"demo",changes:42});
    assert.equal(result.code,"invalid_revision");
    assert.equal(sandbox.exports.validate_card, undefined);
    assert.equal(fs.readFileSync(path.join(root, "app/src/main/assets/packages/dynamic_ui.js"),"utf8"), source);
    console.log("Express package fixtures and diagnostic/reference transport passed.");
})().catch(e => { console.error(e); process.exitCode = 1; });

// Scripts copied from the Java tests (src/test/java/baritone/alto/script/ValidatorTest.java, CodecTest.java,
// Fixtures.java). Single quotes stand for double quotes, exactly like Scripts.j in the Java tests.

export const j = (s) => s.replace(/'/g, '"');
export const script = (variables, procedures, ...events) =>
  j(`{'format':'alto-script','version':1,'name':'test','variables':[${variables}],'procedures':[${procedures}],'events':[${events.join(',')}]}`);
export const startHandler = (...statements) => `{'event':'on_start','body':[${statements.join(',')}]}`;
export const handler = (eventFields, ...statements) => `{${eventFields},'body':[${statements.join(',')}]}`;
export const onStart = (...statements) => script('', '', startHandler(...statements));
export const withVars = (variables, ...statements) => script(variables, '', startHandler(...statements));

const WAIT = "{'block':'wait','seconds':1}";
const COUNT_VAR = "{'name':'count','initial':0}";

/** Fixtures.EVERYTHING: uses every block, expression, event and target kind once; warnings only. */
export const EVERYTHING = j(`
{'format':'alto-script','version':1,'name':'everything','description':'all blocks',
 'variables':[{'name':'count','initial':0},{'name':'label','initial':'x'},{'name':'flag','initial':true}],
 'procedures':[{'name':'restock','params':[{'name':'item','type':'item'},{'name':'amount','type':'number'}],
   'body':[{'block':'if','condition':{'expr':'lt','a':{'expr':'item_count','item':{'expr':'param','name':'item'},'where':'inventory'},
                                      'b':{'expr':'param','name':'amount'}},
            'then':[{'block':'fetch','item':{'expr':'param','name':'item'},'count':{'expr':'param','name':'amount'}}]}]}],
 'events':[
   {'event':'on_start','body':[
      {'block':'goto','target':{'type':'coords','x':1,'y':64,'z':-3,'dimension':'minecraft:overworld'}},
      {'block':'goto','target':{'type':'home','name':'base'}},
      {'block':'goto','target':{'type':'storage_zone','name':'main'}},
      {'block':'goto','target':{'type':'portal'}},
      {'block':'mine','block_or_item':'minecraft:coal_ore','count':16},
      {'block':'build','schematic':'house.schem','x':0,'y':64,'z':0},
      {'block':'fill','material':'minecraft:stone','x1':0,'y1':60,'z1':0,'x2':2,'y2':60,'z2':2},
      {'block':'farm','range':16,'crops':['minecraft:wheat']},
      {'block':'craft','item':'oak_planks','count':4},
      {'block':'smelt','item':'iron_ingot','count':8},
      {'block':'fetch','item':'bread','count':4},
      {'block':'deposit','except':['minecraft:bread'],'only':['minecraft:cobblestone']},
      {'block':'eat'},
      {'block':'wait','seconds':0.5},
      {'block':'message','text':{'expr':'join','parts':['count=',{'expr':'var','name':'count'},' ',
          {'expr':'hunger'},{'expr':'position','axis':'y'},{'expr':'time_of_day'},{'expr':'dimension'},
          {'expr':'block_at','x':0,'y':63,'z':0},{'expr':'inventory_full'},
          {'expr':'sub','a':5,'b':2},{'expr':'mul','a':5,'b':2},{'expr':'div','a':5,'b':2},
          {'expr':'mod','a':5,'b':2},{'expr':'min','a':5,'b':2},{'expr':'max','a':5,'b':2},
          {'expr':'eq','a':'a','b':'a'},{'expr':'neq','a':1,'b':2},{'expr':'lte','a':1,'b':2},
          {'expr':'or','a':false,'b':true}]}},
      {'block':'command','command':'mine diamond_ore'},
      {'block':'send_chat','text':'hello'},
      {'block':'use_item_on_block','item':'bone_meal','x':1,'y':64,'z':1,'face':'up'},
      {'block':'set_filter','x':5,'y':64,'z':5,'face':'north','item':'iron_ingot'},
      {'block':'place_station','kind':'furnace'},
      {'block':'pickup_station','kind':'furnace'},
      {'block':'board_vehicle','kind':'boat'},
      {'block':'ride_train','from_station':'A','to_station':'B'},
      {'block':'leave_vehicle'},
      {'block':'take_portal'},
      {'block':'repeat','times':2,'body':[{'block':'wait','seconds':1}]},
      {'block':'repeat_until','condition':{'expr':'gte','a':{'expr':'var','name':'count'},'b':3},
       'body':[{'block':'change_var','name':'count','delta':1},{'block':'wait','seconds':0}]},
      {'block':'forever','body':[{'block':'wait','seconds':1},{'block':'break'}]},
      {'block':'if','condition':{'expr':'and','a':{'expr':'var','name':'flag'},'b':{'expr':'not','a':false}},
       'then':[{'block':'set_var','name':'label','value':'y'}],
       'else':[{'block':'set_var','name':'flag','value':false}]},
      {'block':'wait_until','condition':{'expr':'gt','a':{'expr':'health'},'b':5},'timeout_seconds':30},
      {'block':'call','procedure':'restock','args':{'item':'minecraft:oak_log','amount':8}},
      {'block':'set_var','name':'count','value':{'expr':'add','a':{'expr':'random','min':1,'max':6},
         'b':{'expr':'round','a':{'expr':'abs','a':-2.5}}}},
      {'block':'stop'}
   ]},
   {'event':'on_chat','trigger':'!go','body':[{'block':'message','text':'go'}]},
   {'event':'on_inventory_full','body':[{'block':'deposit'}]},
   {'event':'on_health_below','health':8,'body':[{'block':'eat'}]},
   {'event':'on_item_below','item':'bread','count':4,'where':'inventory','body':[{'block':'fetch','item':'bread','count':16}]},
   {'event':'every','seconds':300,'body':[{'block':'farm'}]}
 ]}`);

/**
 * Cases: json, expect = [code, path] pairs that must be reported, absent = codes that must not be reported,
 * clean = no diagnostics at all, onlyWarnings = no errors.
 */
export const CASES = [];
const add = (name, json, options) => CASES.push({ name, json, ...options });

// ---- ValidatorTest.TRIGGERS: one script per diagnostic code
add('trigger json_syntax', '{', { expect: [['json_syntax', '']] });
add('trigger too_large', ' '.repeat(1_000_001), { expect: [['too_large', '']] });
add('trigger unsupported_format', j("{'format':'scratch','version':1}"), { expect: [['unsupported_format', 'format']] });
add('trigger unsupported_version', j("{'format':'alto-script','version':9}"), { expect: [['unsupported_version', 'version']] });
add('trigger missing_field', onStart("{'block':'craft','item':'x'}"), { expect: [['missing_field', 'events[0].body[0].count']] });
add('trigger unknown_field', onStart("{'block':'eat','fast':true}"), { expect: [['unknown_field', 'events[0].body[0].fast']] });
add('trigger wrong_json_type', onStart("{'block':'wait','seconds':[1]}"), { expect: [['wrong_json_type', 'events[0].body[0].seconds']] });
add('trigger unknown_block', onStart("{'block':'jump'}"), { expect: [['unknown_block', 'events[0].body[0]']] });
add('trigger unknown_expr', onStart("{'block':'message','text':{'expr':'sqrt','a':4}}"), { expect: [['unknown_expr', 'events[0].body[0].text']] });
add('trigger unknown_event', script('', '', "{'event':'on_death','body':[]}"), { expect: [['unknown_event', 'events[0]']] });
add('trigger invalid_choice', onStart("{'block':'board_vehicle','kind':'horse'}"), { expect: [['invalid_choice', 'events[0].body[0].kind']] });
add('trigger invalid_name', withVars("{'name':'2fast','initial':0}", "{'block':'eat'}"), { expect: [['invalid_name', 'variables[0].name']] });
add('trigger duplicate_name', withVars(COUNT_VAR + ',' + COUNT_VAR, "{'block':'change_var','name':'count','delta':1}"), { expect: [['duplicate_name', 'variables[1].name']] });
add('trigger duplicate_event', script('', '', startHandler(), startHandler()), { expect: [['duplicate_event', 'events[1]']] });
add('trigger no_events', script('', ''), { expect: [['no_events', 'events']] });
add('trigger unknown_variable', onStart("{'block':'set_var','name':'cout','value':1}"), { expect: [['unknown_variable', 'events[0].body[0].name']] });
add('trigger unknown_procedure', onStart("{'block':'call','procedure':'nowhere'}"), { expect: [['unknown_procedure', 'events[0].body[0].procedure']] });
add('trigger unknown_param', script('', "{'name':'p','params':[],'body':[{'block':'message','text':{'expr':'param','name':'q'}}]}",
  startHandler("{'block':'call','procedure':'p'}")), { expect: [['unknown_param', 'procedures[0].body[0].text']] });
add('trigger param_outside_procedure', onStart("{'block':'message','text':{'expr':'param','name':'item'}}"), { expect: [['param_outside_procedure', 'events[0].body[0].text']] });
add('trigger missing_argument', script('', "{'name':'p','params':[{'name':'n','type':'number'}],'body':[]}",
  startHandler("{'block':'call','procedure':'p','args':{}}")), { expect: [['missing_argument', 'events[0].body[0].args']] });
add('trigger unknown_argument', script('', "{'name':'p','params':[],'body':[]}",
  startHandler("{'block':'call','procedure':'p','args':{'n':1}}")), { expect: [['unknown_argument', 'events[0].body[0].args.n']] });
add('trigger type_mismatch', onStart("{'block':'craft','item':'x','count':'many'}"), { expect: [['type_mismatch', 'events[0].body[0].count']] });
add('trigger not_whole_number', onStart("{'block':'craft','item':'x','count':1.5}"), { expect: [['not_whole_number', 'events[0].body[0].count']] });
add('trigger out_of_range', onStart("{'block':'craft','item':'x','count':0}"), { expect: [['out_of_range', 'events[0].body[0].count']] });
add('trigger invalid_id', onStart("{'block':'craft','item':'Oak Planks','count':1}"), { expect: [['invalid_id', 'events[0].body[0].item']] });
add('trigger invalid_file_name', onStart("{'block':'build','schematic':'../secret.schem','x':0,'y':0,'z':0}"), { expect: [['invalid_file_name', 'events[0].body[0].schematic']] });
add('trigger invalid_text', onStart("{'block':'command','command':'  '}"), { expect: [['invalid_text', 'events[0].body[0].command']] });
add('trigger recursion', script('', "{'name':'p','params':[],'body':[{'block':'call','procedure':'p'}]}",
  startHandler("{'block':'call','procedure':'p'}")), { expect: [['recursion', 'procedures[0].body[0]']] });
add('trigger break_outside_loop', onStart("{'block':'break'}"), { expect: [['break_outside_loop', 'events[0].body[0]']] });
add('trigger loop_without_wait', withVars(COUNT_VAR,
  "{'block':'repeat','times':3,'body':[{'block':'change_var','name':'count','delta':1}]}"), { expect: [['loop_without_wait', 'events[0].body[0]']] });
add('trigger forever_loop', onStart(`{'block':'forever','body':[${WAIT}]}`), { expect: [['forever_loop', 'events[0].body[0]']] });
add('trigger sends_chat', onStart("{'block':'send_chat','text':'hi'}"), { expect: [['sends_chat', 'events[0].body[0]']] });
add('trigger runs_command', onStart("{'block':'command','command':'mine diamond_ore'}"), { expect: [['runs_command', 'events[0].body[0]']] });
add('trigger large_count', onStart("{'block':'mine','block_or_item':'stone','count':20000}"), { expect: [['large_count', 'events[0].body[0].count']] });
add('trigger unreachable_code', onStart("{'block':'stop'}", "{'block':'eat'}"), { expect: [['unreachable_code', 'events[0].body[1]']] });
add('trigger unused_procedure', script('', "{'name':'p','params':[],'body':[]}", startHandler()), { expect: [['unused_procedure', 'procedures[0]']] });
add('trigger unused_variable', withVars(COUNT_VAR, "{'block':'eat'}"), { expect: [['unused_variable', 'variables[0]']] });

// ---- clean scripts
add('clean script', withVars(COUNT_VAR,
  "{'block':'repeat','times':3,'body':[{'block':'craft','item':'oak_planks','count':4},{'block':'change_var','name':'count','delta':1}]}",
  "{'block':'message','text':{'expr':'join','parts':['crafted ',{'expr':'var','name':'count'},' times']}}"), { clean: true });
add('everything fixture', EVERYTHING, { onlyWarnings: true, expect: [['forever_loop', 'events[0].body[27]'], ['sends_chat', 'events[0].body[16]'], ['runs_command', 'events[0].body[15]']] });

// ---- names
add('variable name with space', withVars("{'name':'my var','initial':0}", "{'block':'eat'}"), { expect: [['invalid_name', 'variables[0].name']] });
add('procedure name with dash', script('', "{'name':'do-it','params':[{'name':'x y','type':'number'}],'body':[]}", startHandler()), { expect: [['invalid_name', 'procedures[0].name']] });
add('param name with space', script('', "{'name':'p','params':[{'name':'x y','type':'number'}],'body':[]}", startHandler()), { expect: [['invalid_name', 'procedures[0].params[0].name']] });

// ---- duplicates
add('duplicate procedures', script('', "{'name':'p','params':[],'body':[]},{'name':'p','params':[],'body':[]}", startHandler("{'block':'call','procedure':'p'}")), { expect: [['duplicate_name', 'procedures[1].name']] });
add('duplicate params', script('', "{'name':'p','params':[{'name':'a','type':'number'},{'name':'a','type':'item'}],'body':[]}", startHandler()), { expect: [['duplicate_name', 'procedures[0].params[1].name']] });
add('duplicate chat triggers ignore case and spaces', script('', '', handler("'event':'on_chat','trigger':'!go'"), handler("'event':'on_chat','trigger':' !GO '")), { expect: [['duplicate_event', 'events[1]']] });
add('duplicate every with 60 and 60.0', script('', '', handler("'event':'every','seconds':60"), handler("'event':'every','seconds':60.0")), { expect: [['duplicate_event', 'events[1]']] });
add('every 60 and 61 are different', script('', '', handler("'event':'every','seconds':60"), handler("'event':'every','seconds':61")), { absent: ['duplicate_event'] });
add('blank script name', onStart().replace('"name":"test"', '"name":" "'), { expect: [['invalid_text', 'name']] });

// ---- references
add('unknown variable hint', withVars(COUNT_VAR, "{'block':'change_var','name':'cout','delta':1}"), { expect: [['unknown_variable', 'events[0].body[0].name']], messageContains: "did you mean 'count'" });
add('unknown variable in expression', withVars(COUNT_VAR, "{'block':'message','text':{'expr':'var','name':'nope'}}", "{'block':'change_var','name':'count','delta':1}"), { expect: [['unknown_variable', 'events[0].body[0].text']] });
add('unknown procedure hint', script('', "{'name':'restock','params':[],'body':[]}", startHandler("{'block':'call','procedure':'restok'}")), { expect: [['unknown_procedure', 'events[0].body[0].procedure']], messageContains: "did you mean 'restock'" });
add('unknown param hint', script('', "{'name':'p','params':[{'name':'item','type':'item'}],'body':[{'block':'craft','item':{'expr':'param','name':'itme'},'count':1}]}",
  startHandler("{'block':'call','procedure':'p','args':{'item':'x'}}")), { expect: [['unknown_param', 'procedures[0].body[0].item']], messageContains: "did you mean 'item'" });
add('param in handler', onStart("{'block':'craft','item':{'expr':'param','name':'item'},'count':1}"), { expect: [['param_outside_procedure', 'events[0].body[0].item']] });

const PROCS = "{'name':'p','params':[{'name':'item','type':'item'},{'name':'n','type':'number'}],'body':[]}";
add('call missing argument', script('', PROCS, startHandler("{'block':'call','procedure':'p','args':{'item':'oak_log'}}")), { expect: [['missing_argument', 'events[0].body[0].args']] });
add('call unknown argument', script('', PROCS, startHandler("{'block':'call','procedure':'p','args':{'item':'oak_log','n':1,'m':2}}")), { expect: [['unknown_argument', 'events[0].body[0].args.m']] });
add('call argument type', script('', PROCS, startHandler("{'block':'call','procedure':'p','args':{'item':5,'n':1}}")), { expect: [['type_mismatch', 'events[0].body[0].args.item']] });
add('call argument id', script('', PROCS, startHandler("{'block':'call','procedure':'p','args':{'item':'Oak Log','n':1}}")), { expect: [['invalid_id', 'events[0].body[0].args.item']] });
add('call ok', script('', PROCS.replace("'body':[]", "'body':[{'block':'craft','item':{'expr':'param','name':'item'},'count':{'expr':'param','name':'n'}}]"),
  startHandler("{'block':'call','procedure':'p','args':{'n':2,'item':'oak_planks'}}")), { clean: true });

// ---- types
add('if condition number', onStart("{'block':'if','condition':1,'then':[]}"), { expect: [['type_mismatch', 'events[0].body[0].condition']] });
add('set_var wrong type', withVars(COUNT_VAR, "{'block':'set_var','name':'count','value':'ten'}"), { expect: [['type_mismatch', 'events[0].body[0].value']] });
add('change_var on string', withVars("{'name':'label','initial':'a'}", "{'block':'change_var','name':'label','delta':1}"), { expect: [['type_mismatch', 'events[0].body[0].name']] });
add('eq mixed types', onStart("{'block':'message','text':{'expr':'eq','a':1,'b':'1'}}"), { expect: [['type_mismatch', 'events[0].body[0].text']] });
add('add boolean', onStart("{'block':'message','text':{'expr':'add','a':1,'b':true}}"), { expect: [['type_mismatch', 'events[0].body[0].text.b']] });
add('not number', onStart("{'block':'message','text':{'expr':'not','a':0}}"), { expect: [['type_mismatch', 'events[0].body[0].text.a']] });
add('wait_until number', onStart("{'block':'wait_until','condition':{'expr':'health'}}"), { expect: [['type_mismatch', 'events[0].body[0].condition']] });
add('coords string', onStart("{'block':'goto','target':{'type':'coords','x':'a','y':1,'z':1}}"), { expect: [['type_mismatch', 'events[0].body[0].target.x']] });
add('item number', onStart("{'block':'craft','item':{'expr':'health'},'count':1}"), { expect: [['type_mismatch', 'events[0].body[0].item']] });
add('typed variables ok', withVars("{'name':'item','initial':'oak_log'},{'name':'n','initial':2},{'name':'on','initial':true}",
  "{'block':'if','condition':{'expr':'var','name':'on'},'then':[{'block':'craft','item':{'expr':'var','name':'item'},'count':{'expr':'mul','a':{'expr':'var','name':'n'},'b':4}}]}"), { clean: true });

// ---- ranges
add('count too large', onStart("{'block':'craft','item':'x','count':100001}"), { expect: [['out_of_range', 'events[0].body[0].count']] });
add('wait negative', onStart("{'block':'wait','seconds':-1}"), { expect: [['out_of_range', 'events[0].body[0].seconds']] });
add('wait too long', onStart("{'block':'wait','seconds':86401}"), { expect: [['out_of_range', 'events[0].body[0].seconds']] });
add('repeat too many', onStart(`{'block':'repeat','times':100001,'body':[${WAIT}]}`), { expect: [['out_of_range', 'events[0].body[0].times']] });
add('repeat fraction', onStart(`{'block':'repeat','times':2.5,'body':[${WAIT}]}`), { expect: [['not_whole_number', 'events[0].body[0].times']] });
add('coords fraction', onStart("{'block':'goto','target':{'type':'coords','x':0.5,'y':1,'z':1}}"), { expect: [['not_whole_number', 'events[0].body[0].target.x']] });
add('coords outside border', onStart("{'block':'goto','target':{'type':'coords','x':40000000,'y':1,'z':1}}"), { expect: [['out_of_range', 'events[0].body[0].target.x']] });
add('timeout too long', onStart("{'block':'wait_until','condition':true,'timeout_seconds':90000}"), { expect: [['out_of_range', 'events[0].body[0].timeout_seconds']] });
add('random too large', onStart("{'block':'message','text':{'expr':'random','min':0,'max':5000000000}}"), { expect: [['out_of_range', 'events[0].body[0].text.max']] });
add('farm range zero', onStart("{'block':'farm','range':0}"), { expect: [['out_of_range', 'events[0].body[0].range']] });
add('deposit empty list', onStart("{'block':'deposit','only':[]}"), { expect: [['out_of_range', 'events[0].body[0].only']] });
add('send_chat too long', onStart(`{'block':'send_chat','text':'${'x'.repeat(257)}'}`), { expect: [['out_of_range', 'events[0].body[0].text']] });
add('every half second', script('', '', handler("'event':'every','seconds':0.5")), { expect: [['out_of_range', 'events[0].seconds']] });
add('health zero', script('', '', handler("'event':'on_health_below','health':0")), { expect: [['out_of_range', 'events[0].health']] });
add('item below fraction', script('', '', handler("'event':'on_item_below','item':'bread','count':2.5")), { expect: [['not_whole_number', 'events[0].count']] });
add('wait bounds ok', onStart("{'block':'wait','seconds':0}", "{'block':'wait','seconds':86400}"), { absent: ['out_of_range'] });

// ---- ids, file names, texts
add('deposit bad id', onStart("{'block':'deposit','except':['bread','Bad Id']}"), { expect: [['invalid_id', 'events[0].body[0].except[1]']] });
add('item_count bad id', onStart("{'block':'message','text':{'expr':'item_count','item':'a b'}}"), { expect: [['invalid_id', 'events[0].body[0].text.item']] });
add('item below upper case', script('', '', handler("'event':'on_item_below','item':'BREAD','count':2")), { expect: [['invalid_id', 'events[0].item']] });
add('dimension bad id', onStart("{'block':'goto','target':{'type':'coords','x':1,'y':1,'z':1,'dimension':'The Nether'}}"), { expect: [['invalid_id', 'events[0].body[0].target.dimension']] });
for (const bad of ['../x.schem', 'a/b.schem', 'C:\\\\x.schem', '.hidden', '']) {
  add(`bad schematic ${bad}`, onStart(`{'block':'build','schematic':'${bad}','x':0,'y':0,'z':0}`), { expect: [['invalid_file_name', 'events[0].body[0].schematic']] });
}
add('schematic with spaces ok', onStart("{'block':'build','schematic':'My House (v2).schem','x':0,'y':0,'z':0}"), { absent: ['invalid_file_name'] });
add('command with slash', onStart("{'block':'command','command':'/give @s diamond'}"), { expect: [['invalid_text', 'events[0].body[0].command']] });
add('chat trigger with slash', script('', '', handler("'event':'on_chat','trigger':'/go'")), { expect: [['invalid_text', 'events[0].trigger']] });
add('empty chat trigger', script('', '', handler("'event':'on_chat','trigger':''")), { expect: [['invalid_text', 'events[0].trigger']] });
add('empty storage zone', onStart("{'block':'goto','target':{'type':'storage_zone','name':''}}"), { expect: [['invalid_text', 'events[0].body[0].target.name']] });
add('blank station', onStart("{'block':'ride_train','from_station':' ','to_station':'B'}"), { expect: [['invalid_text', 'events[0].body[0].from_station']] });

// ---- control flow
add('indirect recursion', script('', `{'name':'a','params':[],'body':[{'block':'call','procedure':'b'}]},{'name':'b','params':[],'body':[${WAIT},{'block':'call','procedure':'a'}]}`,
  startHandler("{'block':'call','procedure':'a'}")), { expect: [['recursion', 'procedures[1].body[1]']], messageContains: 'a -> b -> a' });
add('calling twice is fine', script('', `{'name':'a','params':[],'body':[${WAIT}]},{'name':'b','params':[],'body':[{'block':'call','procedure':'a'},{'block':'call','procedure':'a'}]}`,
  startHandler("{'block':'call','procedure':'b'}")), { absent: ['recursion'] });
add('break in if', onStart("{'block':'if','condition':true,'then':[{'block':'break'}]}"), { expect: [['break_outside_loop', 'events[0].body[0].then[0]']] });
add('break in procedure', script('', "{'name':'p','params':[],'body':[{'block':'break'}]}", startHandler(`{'block':'forever','body':[${WAIT},{'block':'call','procedure':'p'}]}`)),
  { expect: [['break_outside_loop', 'procedures[0].body[0]']], messageContains: 'caller' });
add('break in loop ok', onStart(`{'block':'repeat','times':2,'body':[${WAIT},{'block':'if','condition':true,'then':[{'block':'break'}]}]}`), { absent: ['break_outside_loop'] });
add('repeat_until without wait', withVars(COUNT_VAR, "{'block':'repeat_until','condition':false,'body':[{'block':'change_var','name':'count','delta':1}]}"),
  { expect: [['loop_without_wait', 'events[0].body[0]']], messageContains: 'loop without a wait or task step' });
add('forever with message only', onStart("{'block':'forever','body':[{'block':'message','text':'spam'}]}"), { expect: [['loop_without_wait', 'events[0].body[0]']] });
add('empty repeat', onStart("{'block':'repeat','times':5,'body':[]}"), { expect: [['loop_without_wait', 'events[0].body[0]']] });
add('procedure without wait', script('', "{'name':'p','params':[],'body':[{'block':'message','text':'x'}]}",
  startHandler("{'block':'repeat','times':5,'body':[{'block':'call','procedure':'p'}]}")), { expect: [['loop_without_wait', 'events[0].body[0]']] });
add('inner loop without wait', withVars(COUNT_VAR, `{'block':'forever','body':[${WAIT},{'block':'repeat','times':3,'body':[{'block':'change_var','name':'count','delta':1}]}]}`),
  { expect: [['loop_without_wait', 'events[0].body[0].body[1]']] });
for (const body of [WAIT, "{'block':'wait_until','condition':true}", "{'block':'eat'}", "{'block':'send_chat','text':'x'}",
  `{'block':'if','condition':true,'then':[],'else':[${WAIT}]}`, "{'block':'call','procedure':'w'}"]) {
  add(`loop yields with ${body}`, script('', "{'name':'w','params':[],'body':[{'block':'goto','target':{'type':'home'}}]}",
    startHandler(`{'block':'repeat','times':2,'body':[${body}]}`, "{'block':'call','procedure':'w'}")), { absent: ['loop_without_wait'] });
}

// ---- warnings
add('chat with slash', onStart("{'block':'send_chat','text':'/spawn'}"), { expect: [['sends_chat', 'events[0].body[0]']], messageContains: 'server runs it as a command' });
add('command with hash', onStart("{'block':'command','command':'#mine diamond_ore'}"), { expect: [['runs_command', 'events[0].body[0]']], messageContains: "'mine diamond_ore'" });
add('large repeat', onStart(`{'block':'repeat','times':50000,'body':[${WAIT}]}`), { expect: [['large_count', 'events[0].body[0].times']] });
add('large fill', onStart("{'block':'fill','material':'stone','x1':0,'y1':0,'z1':0,'x2':99,'y2':99,'z2':99}"), { expect: [['large_count', 'events[0].body[0]']] });
add('normal craft', onStart("{'block':'craft','item':'x','count':64}"), { absent: ['large_count'] });
add('unreachable once per list', onStart("{'block':'stop'}", "{'block':'eat'}", "{'block':'eat'}"), { expect: [['unreachable_code', 'events[0].body[1]']], countOf: ['unreachable_code', 1] });
add('unreachable after break', onStart(`{'block':'repeat','times':2,'body':[${WAIT},{'block':'break'},${WAIT}]}`), { expect: [['unreachable_code', 'events[0].body[0].body[2]']] });
add('unreachable after forever', onStart(`{'block':'forever','body':[${WAIT}]}`, "{'block':'eat'}"), { expect: [['unreachable_code', 'events[0].body[1]']] });
add('forever with break is fine', onStart(`{'block':'forever','body':[${WAIT},{'block':'if','condition':true,'then':[{'block':'break'}]}]}`, "{'block':'eat'}"), { absent: ['unreachable_code'] });
add('unused procedure', script('', "{'name':'helper','params':[],'body':[]}", startHandler()), { expect: [['unused_procedure', 'procedures[0]']] });

// ---- structure (CodecTest)
add('syntax: truncated', '{"format": ', { expect: [['json_syntax', '']] });
add('syntax: trailing text', onStart("{'block':'eat'}") + ' trailing', { expect: [['json_syntax', '']] });
add('syntax: comment', onStart("{'block':'eat'} /* comment */"), { expect: [['json_syntax', '']] });
add('duplicate keys', onStart("{'block':'eat','block':'eat'}"), { expect: [['json_syntax', 'events[0].body[0].block']] });
add('other format', j("{'format':'other','version':1,'name':'x','events':[]}"), { expect: [['unsupported_format', 'format']] });
add('version 2', j("{'format':'alto-script','version':2,'name':'x','events':[]}"), { expect: [['unsupported_version', 'version']], messageContains: 'version 2' });
add('array root', '[1,2]', { expect: [['wrong_json_type', '']] });
add('unknown block hint', onStart("{'block':'eat'}", "{'block':'crafts','item':'x','count':1}"), { expect: [['unknown_block', 'events[0].body[1]']], messageContains: "did you mean 'craft'" });
add('unknown expr', onStart("{'block':'message','text':{'expr':'healt'}}"), { expect: [['unknown_expr', 'events[0].body[0].text']] });
add('unknown event', script('', '', "{'event':'on_begin','body':[]}"), { expect: [['unknown_event', 'events[0]']] });
add('missing times', script('', '', "{'event':'on_start','body':[{'block':'eat'},{'block':'eat'},{'block':'repeat','body':[{'block':'wait','seconds':1}]}]}"), { expect: [['missing_field', 'events[0].body[2].times']] });
add('typo cout', onStart("{'block':'craft','item':'x','cout':3}"), { expect: [['unknown_field', 'events[0].body[0].cout'], ['missing_field', 'events[0].body[0].count']], messageContains: "did you mean 'count'" });
add('missing name', j("{'format':'alto-script','version':1,'events':[]}"), { expect: [['missing_field', 'name']] });
add('extra top field', j("{'format':'alto-script','version':1,'name':'x','events':[],'extra':1}"), { expect: [['unknown_field', 'extra']] });
add('times array', onStart("{'block':'repeat','times':[1],'body':[]}"), { expect: [['wrong_json_type', 'events[0].body[0].times']] });
add('body object', onStart("{'block':'repeat','times':1,'body':{}}"), { expect: [['wrong_json_type', 'events[0].body[0].body']] });
add('computed command', onStart("{'block':'command','command':{'expr':'var','name':'x'}}"), { expect: [['wrong_json_type', 'events[0].body[0].command']] });
add('except string', onStart("{'block':'deposit','except':'bread'}"), { expect: [['wrong_json_type', 'events[0].body[0].except']] });
add('null seconds', onStart("{'block':'wait','seconds':null}"), { expect: [['wrong_json_type', 'events[0].body[0].seconds']] });
add('computed initial', withVars("{'name':'v','initial':{'expr':'health'}}", "{'block':'eat'}"), { expect: [['wrong_json_type', 'variables[0].initial']] });
add('seconds as text', script('', '', "{'event':'every','seconds':'5','body':[]}"), { expect: [['wrong_json_type', 'events[0].seconds']] });
add('anvil station', onStart("{'block':'place_station','kind':'anvil'}"), { expect: [['invalid_choice', 'events[0].body[0].kind']] });
add('where chest', onStart("{'block':'message','text':{'expr':'item_count','item':'x','where':'chest'}}"), { expect: [['invalid_choice', 'events[0].body[0].text.where']] });
add('target moon', onStart("{'block':'goto','target':{'type':'moon'}}"), { expect: [['invalid_choice', 'events[0].body[0].target.type']] });
add('param type list', script('', "{'name':'p','params':[{'name':'a','type':'list'}],'body':[]}", startHandler()), { expect: [['invalid_choice', 'procedures[0].params[0].type']] });
add('all structural errors in one pass', onStart("{'block':'nope'}", "{'block':'craft'}", "{'block':'wait','seconds':1,'x':2}"),
  { codesExactly: ['unknown_block', 'missing_field', 'missing_field', 'unknown_field'] });
{
  let deep = '';
  for (let i = 0; i < 105; i++) deep += "{'expr':'not','a':";
  deep += 'true' + '}'.repeat(105);
  add('nested too deep', onStart(`{'block':'message','text':${deep}}`), { expect: [['json_syntax', null]], pathStartsWith: 'events[0].body[0].text.a.a' });
}
{
  const many = Array.from({ length: 20001 }, () => "{'block':'eat'}").join(',');
  add('too many blocks', onStart(many), { expectCodes: ['too_large'] });
  add('too many literals', onStart(`{'block':'message','text':{'expr':'join','parts':[${'1,'.repeat(20000)}1]}}`), { expectCodes: ['too_large'] });
}

// ---- extra cases for the editor (escaping, numbers, canonical form)
add('unicode and escapes', onStart("{'block':'wait','seconds':0.25}", "{'block':'message','text':'tab\\t nl\\n bell\\u0007 quote\\\" slash\\\\\\\\ ls\\u2028 e\u00e9 <&>'}"), { clean: true });
add('small and large numbers', onStart("{'block':'wait','seconds':0.00001}", "{'block':'message','text':{'expr':'add','a':12345678.5,'b':1e16}}"), { clean: true });
add('number display', onStart("{'block':'wait','seconds':86400.5}"), { expect: [['out_of_range', 'events[0].body[0].seconds']] });
add('fraction display', onStart("{'block':'craft','item':'x','count':0.333333333333333}"), { expect: [['not_whole_number', 'events[0].body[0].count']] });
add('unknown arg sorting', script('', "{'name':'p','params':[],'body':[]}", startHandler("{'block':'call','procedure':'p','args':{'b':1,'a':2,'B':3}}")),
  { expect: [['unknown_argument', 'events[0].body[0].args.B'], ['unknown_argument', 'events[0].body[0].args.a'], ['unknown_argument', 'events[0].body[0].args.b']] });
add('item below normalized duplicate', script('', '', handler("'event':'on_item_below','item':'bread','count':4"), handler("'event':'on_item_below','item':'minecraft:bread','count':4,'where':'inventory'")),
  { expect: [['duplicate_event', 'events[1]']] });
add('huge fill coordinates', onStart("{'block':'fill','material':'stone','x1':-30000000,'y1':-64,'z1':-30000000,'x2':30000000,'y2':320,'z2':30000000}"), { expect: [['large_count', 'events[0].body[0]']] });
add('variable text too long', withVars(`{'name':'t','initial':'${'y'.repeat(1001)}'}`, "{'block':'message','text':{'expr':'var','name':'t'}}"), { expect: [['out_of_range', 'variables[0].initial']] });
add('description too long', j(`{'format':'alto-script','version':1,'name':'n','description':'${'d'.repeat(4001)}','events':[{'event':'on_start','body':[]}]}`), { expect: [['out_of_range', 'description']] });

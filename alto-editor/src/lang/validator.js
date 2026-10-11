// Checks the meaning of a parsed script, mirroring baritone.alto.script.check.ScriptValidator: names, references,
// static types, literal ranges, recursion, break placement, the loop guard and warnings. Same codes, paths, messages
// and order as the Java validator.

import {
  BOOLEAN, FIELDS, LIMITS, NUMBER, STRING, SENSORS, UNARY_OPS, actionYields, binaryOperandType, binaryResultType,
  checkNumber, checkText, diagnostic, fieldValueType, isValidId, isValidName, normalizeCommand, normalizeId,
  paramIsId, paramValueType, withArticle,
} from './catalog.js';
import { formatNumber, hint, javaTrim } from './javaish.js';
import { join } from './reader.js';

/** Event.label(). */
export function eventLabel(event) {
  switch (event.type) {
    case 'on_chat':
      return `on_chat "${javaTrim(event.trigger)}"`;
    case 'on_health_below':
      return 'on_health_below ' + formatNumber(event.health);
    case 'on_item_below': {
      const id = isValidId(event.item) ? normalizeId(event.item) : event.item;
      return `on_item_below ${id} ${formatNumber(event.count)} ${event.where}`;
    }
    case 'every':
      return `every ${formatNumber(event.seconds)} s`;
    default:
      return event.type;
  }
}

function literalDisplay(value) {
  if (value.type === 'number') return formatNumber(value.value);
  return String(value.value);
}

function actionExpr(a, field) {
  const arg = a.args.get(field);
  return arg && arg.kind === 'expr' ? arg.expr : null;
}

/** All errors and warnings in a deterministic order (declarations, procedures, events, whole-script checks). */
export function validate(script) {
  const out = [];
  const variables = new Map();
  const procedures = new Map();
  const usedVariables = new Set();
  const calledProcedures = new Set();
  const callGraph = new Map(); // caller -> Map(callee -> path of the first call)
  const procedureYieldsCache = new Map();

  const error = (code, path, message) => out.push(diagnostic(code, path, message));
  const warning = error;
  const problem = (p, path) => {
    if (p) error(p.code, path, p.message);
  };

  // ---------------------------------------------------------------------------------------- declarations

  problem(checkText(FIELDS.SCRIPT_NAME, script.name), 'name');
  problem(checkText(FIELDS.SCRIPT_DESCRIPTION, script.description), 'description');

  const checkName = (name, path, what) => {
    if (!isValidName(name)) {
      error('invalid_name', path, `${what} names must start with a letter or _ and contain only letters, digits and _ (at most ${LIMITS.MAX_NAME_LENGTH} characters), got '${name}'`);
    }
  };

  for (const v of script.variables) {
    const path = join(v.path, 'name');
    checkName(v.name, path, 'variable');
    if (variables.has(v.name)) {
      error('duplicate_name', path, `there is already a variable called '${v.name}'`);
    } else {
      variables.set(v.name, v);
    }
    if (v.initial.type === 'string' && v.initial.value.length > LIMITS.MAX_TEXT_LENGTH) {
      error('out_of_range', join(v.path, 'initial'), `initial must be at most ${LIMITS.MAX_TEXT_LENGTH} characters`);
    }
  }
  for (const p of script.procedures) {
    const path = join(p.path, 'name');
    checkName(p.name, path, 'procedure');
    if (procedures.has(p.name)) {
      error('duplicate_name', path, `there is already a procedure called '${p.name}'`);
    } else {
      procedures.set(p.name, p);
    }
    const params = new Set();
    for (const param of p.params) {
      const paramPath = join(param.path, 'name');
      checkName(param.name, paramPath, 'parameter');
      if (params.has(param.name)) {
        error('duplicate_name', paramPath, `procedure '${p.name}' already has a parameter called '${param.name}'`);
      }
      params.add(param.name);
    }
  }

  const procParam = (procedure, name) => procedure.params.find((x) => x.name === name) || null;

  // ---------------------------------------------------------------------------------------- expressions

  const variable = (name, path) => {
    const v = variables.get(name);
    if (!v) {
      error('unknown_variable', path, `there is no variable called '${name}'${hint(name, variables.keys())}`);
      return null;
    }
    usedVariables.add(name);
    return v;
  };

  const param = (p, scope) => {
    if (scope.procedure == null) {
      error('param_outside_procedure', p.path, `param '${p.name}' can only be used inside a procedure`);
      return null;
    }
    const d = procParam(scope.procedure, p.name);
    if (!d) {
      const names = scope.procedure.params.map((x) => x.name);
      error('unknown_param', p.path, `procedure '${scope.procedure.name}' has no parameter '${p.name}'${hint(p.name, names)}`);
      return null;
    }
    return paramValueType(d.type);
  };

  const operand = (e, got, want, op) => {
    if (got != null && got !== want) {
      error('type_mismatch', e.path, `${op} needs ${withArticle(want)} here, but this is ${withArticle(got)}`);
    }
  };

  const typeOf = (e, scope) => {
    switch (e.kind) {
      case 'literal':
        return e.value.type;
      case 'var': {
        const d = variable(e.name, e.path);
        return d == null ? null : d.initial.type;
      }
      case 'param':
        return param(e, scope);
      case 'item_count':
        checkField(FIELDS.ITEM, e.item, scope);
        return NUMBER;
      case 'sensor':
        return SENSORS[e.sensor];
      case 'position':
        return NUMBER;
      case 'block_at':
        checkField(FIELDS.BLOCK_X, e.x, scope);
        checkField(FIELDS.BLOCK_Y, e.y, scope);
        checkField(FIELDS.BLOCK_Z, e.z, scope);
        return STRING;
      case 'random':
        checkField(FIELDS.RANDOM_MIN, e.min, scope);
        checkField(FIELDS.RANDOM_MAX, e.max, scope);
        return NUMBER;
      case 'binary': {
        const ta = typeOf(e.a, scope);
        const tb = typeOf(e.b, scope);
        const want = binaryOperandType(e.op);
        if (want != null) {
          operand(e.a, ta, want, e.op);
          operand(e.b, tb, want, e.op);
        } else if (ta != null && tb != null && ta !== tb) {
          error('type_mismatch', e.path, `${e.op} compares two values of the same type, but got ${withArticle(ta)} and ${withArticle(tb)}`);
        }
        return binaryResultType(e.op);
      }
      case 'unary':
        operand(e.a, typeOf(e.a, scope), UNARY_OPS[e.op], e.op);
        return UNARY_OPS[e.op];
      case 'join':
        for (const part of e.parts) typeOf(part, scope);
        return STRING;
      default:
        return null;
    }
  };

  /** Type-checks e against a field and checks literal values against its range/format. */
  function checkField(f, e, scope) {
    const want = fieldValueType(f);
    const got = typeOf(e, scope);
    if (want != null && got != null && got !== want) {
      error('type_mismatch', e.path, `${f.name} must be ${withArticle(want)}, but this is ${withArticle(got)}`);
      return;
    }
    if (e.kind === 'literal') {
      if (e.value.type === 'number' && want === NUMBER) {
        problem(checkNumber(f, e.value.value), e.path);
      } else if (e.value.type === 'string') {
        problem(checkText(f, e.value.value), e.path);
      }
    }
  }

  const expect = (e, want, what, scope) => {
    const got = typeOf(e, scope);
    if (got != null && got !== want) {
      error('type_mismatch', e.path, `${what} must be ${withArticle(want)}, but this is ${withArticle(got)}`);
    }
  };
  const condition = (e, scope) => expect(e, BOOLEAN, 'condition', scope);

  // ---------------------------------------------------------------------------------------- loop guard

  const yieldsBody = (body) => body.some((s) => yieldsStmt(s));
  function yieldsStmt(s) {
    switch (s.stmt) {
      case 'action':
        return actionYields(s.type);
      case 'wait_until':
        return true;
      case 'repeat':
      case 'repeat_until':
      case 'forever':
        return yieldsBody(s.body);
      case 'if':
        return yieldsBody(s.then) || yieldsBody(s.otherwise);
      case 'call':
        return procedureYields(s.procedure);
      default:
        return false;
    }
  }
  function procedureYields(name) {
    if (procedureYieldsCache.has(name)) return procedureYieldsCache.get(name);
    const p = procedures.get(name);
    if (!p) return false;
    procedureYieldsCache.set(name, false); // cycle guard; recursion is reported separately
    const result = yieldsBody(p.body);
    procedureYieldsCache.set(name, result);
    return result;
  }

  // ---------------------------------------------------------------------------------------- statements

  const breaksOut = (body) => body.some((s) => s.stmt === 'break' || (s.stmt === 'if' && (breaksOut(s.then) || breaksOut(s.otherwise))));

  const endsFlow = (s) => {
    if (s.stmt === 'break') return 'always leaves the loop';
    if (s.stmt === 'action' && s.type.name === 'stop') return 'always stops the script';
    if (s.stmt === 'forever' && !breaksOut(s.body)) return 'loops forever (it has no break)';
    return null;
  };

  function body(list, scope) {
    let endedBecause = null;
    let reported = false;
    for (const s of list) {
      if (endedBecause != null && !reported) {
        warning('unreachable_code', s.path, `this block never runs: the block before it ${endedBecause}`);
        reported = true;
      }
      statement(s, scope);
      if (endedBecause == null) endedBecause = endsFlow(s);
    }
  }

  function loop(path, list, scope) {
    if (!yieldsBody(list)) {
      error('loop_without_wait', path, 'loop without a wait or task step: put a wait block or an action such as craft or goto inside it, otherwise it would spin without giving the game time');
    }
    body(list, { procedure: scope.procedure, loopDepth: scope.loopDepth + 1 });
  }

  const largeCount = (e, what) => {
    if (e.kind === 'literal' && e.value.type === 'number' && e.value.value >= LIMITS.LARGE_COUNT && e.value.value <= LIMITS.MAX_COUNT) {
      warning('large_count', e.path, `${what} is ${literalDisplay(e.value)}; that takes a very long time`);
    }
  };

  function target(t, path, scope) {
    switch (t.type) {
      case 'coords':
        checkField(FIELDS.BLOCK_X, t.x, scope);
        checkField(FIELDS.BLOCK_Y, t.y, scope);
        checkField(FIELDS.BLOCK_Z, t.z, scope);
        if (t.dimension != null) problem(checkText(FIELDS.DIMENSION, t.dimension), join(path, 'dimension'));
        break;
      case 'home':
        if (t.name != null) problem(checkText(FIELDS.PLACE_NAME, t.name), join(path, 'name'));
        break;
      case 'storage_zone':
        problem(checkText(FIELDS.PLACE_NAME, t.name), join(path, 'name'));
        break;
      default:
        break;
    }
  }

  /** (long) cast of a double, as Java does it (saturating, NaN to 0). */
  const toLong = (v) => {
    if (Number.isNaN(v)) return 0n;
    if (v >= 9223372036854775807) return 9223372036854775807n;
    if (v <= -9223372036854775808) return -9223372036854775808n;
    return BigInt(Math.trunc(v));
  };
  const wrap = (x) => BigInt.asIntN(64, x);
  const labs = (x) => wrap(x < 0n ? -x : x);

  function fillVolume(a) {
    const names = ['x1', 'y1', 'z1', 'x2', 'y2', 'z2'];
    const c = [];
    for (const n of names) {
      const e = actionExpr(a, n);
      if (!e || e.kind !== 'literal' || e.value.type !== 'number') return;
      c.push(toLong(e.value.value));
    }
    const dx = wrap(labs(wrap(c[3] - c[0])) + 1n);
    const dy = wrap(labs(wrap(c[4] - c[1])) + 1n);
    const dz = wrap(labs(wrap(c[5] - c[2])) + 1n);
    const volume = wrap(wrap(dx * dy) * dz);
    if (volume > BigInt(LIMITS.LARGE_FILL_VOLUME)) {
      warning('large_count', a.path, `fill covers ${volume} blocks; that takes a very long time`);
    }
  }

  function action(a, scope) {
    for (const f of a.type.fields) {
      const arg = a.args.get(f.name);
      if (!arg) continue;
      const path = join(a.path, f.name);
      switch (arg.kind) {
        case 'expr':
          checkField(f, arg.expr, scope);
          break;
        case 'list':
          if (arg.items.length === 0) {
            error('out_of_range', path, `${f.name} must list at least one item; leave the field out instead`);
          }
          for (const item of arg.items) checkField(f, item, scope);
          break;
        case 'text':
          problem(checkText(f, arg.text), path);
          break;
        case 'target':
          target(arg.target, path, scope);
          break;
        default:
          break;
      }
    }
    const count = actionExpr(a, 'count');
    if (count != null) largeCount(count, a.type.name + ' count');
    if (a.type.name === 'command') {
      const arg = a.args.get('command');
      if (arg && arg.kind === 'text') {
        const command = normalizeCommand(arg.text);
        if (command.startsWith('/')) {
          error('invalid_text', join(a.path, 'command'), "command takes a Baritone command such as 'mine diamond_ore'; text starting with '/' is a server command (see send_chat)");
        }
        warning('runs_command', a.path, `runs the Baritone command '${command}'; it only runs when it is on the allowed commands list in Alto's settings`);
      }
    } else if (a.type.name === 'send_chat') {
      const text = actionExpr(a, 'text');
      const slash = text != null && text.kind === 'literal' && text.value.type === 'string' && javaTrim(text.value.value).startsWith('/');
      warning('sends_chat', a.path, 'sends a real chat message as you'
        + (slash ? " (this text starts with '/', so the server runs it as a command)" : '')
        + "; Alto refuses it unless scripts are allowed to chat in its settings");
    } else if (a.type.name === 'fill') {
      fillVolume(a);
    }
  }

  function call(c, scope) {
    const p = procedures.get(c.procedure);
    const argsPath = join(c.path, 'args');
    if (!p) {
      error('unknown_procedure', join(c.path, 'procedure'), `there is no procedure called '${c.procedure}'${hint(c.procedure, procedures.keys())}`);
      for (const arg of c.args.values()) typeOf(arg, scope);
      return;
    }
    calledProcedures.add(p.name);
    if (scope.procedure != null) {
      if (!callGraph.has(scope.procedure.name)) callGraph.set(scope.procedure.name, new Map());
      const calls = callGraph.get(scope.procedure.name);
      if (!calls.has(p.name)) calls.set(p.name, c.path);
    }
    for (const decl of p.params) {
      const arg = c.args.get(decl.name);
      if (!arg) {
        error('missing_argument', argsPath, `procedure '${p.name}' needs a value for parameter '${decl.name}' (${decl.type})`);
        continue;
      }
      const type = typeOf(arg, scope);
      const want = paramValueType(decl.type);
      if (type != null && type !== want) {
        error('type_mismatch', arg.path, `parameter '${decl.name}' is ${decl.type} (${withArticle(want)}), but this is ${withArticle(type)}`);
      } else if (paramIsId(decl.type) && arg.kind === 'literal' && arg.value.type === 'string' && !isValidId(arg.value.value)) {
        error('invalid_id', arg.path, `parameter '${decl.name}' needs an item or block id like minecraft:oak_planks, got '${arg.value.value}'`);
      }
    }
    for (const [key, value] of c.args) {
      if (procParam(p, key) == null) {
        const names = p.params.map((x) => x.name);
        error('unknown_argument', join(argsPath, key), `procedure '${p.name}' has no parameter '${key}'${hint(key, names)}`);
        typeOf(value, scope);
      }
    }
  }

  function statement(s, scope) {
    switch (s.stmt) {
      case 'action':
        action(s, scope);
        break;
      case 'repeat':
        checkField(FIELDS.REPEAT_TIMES, s.times, scope);
        largeCount(s.times, 'repeat times');
        loop(s.path, s.body, scope);
        break;
      case 'repeat_until':
        condition(s.condition, scope);
        loop(s.path, s.body, scope);
        break;
      case 'forever':
        warning('forever_loop', s.path, 'forever runs until the script is stopped; Alto only starts it when safety stops are enabled in its settings');
        loop(s.path, s.body, scope);
        break;
      case 'if':
        condition(s.condition, scope);
        body(s.then, scope);
        body(s.otherwise, scope);
        break;
      case 'wait_until':
        condition(s.condition, scope);
        if (s.timeout != null) checkField(FIELDS.TIMEOUT_SECONDS, s.timeout, scope);
        break;
      case 'break':
        if (scope.loopDepth === 0) {
          error('break_outside_loop', s.path, scope.procedure == null
            ? 'break must be inside a repeat, repeat_until or forever block'
            : "break must be inside a loop of the same procedure (it cannot leave the caller's loop)");
        }
        break;
      case 'call':
        call(s, scope);
        break;
      case 'set_var': {
        const v = variable(s.name, join(s.path, 'name'));
        const type = typeOf(s.value, scope);
        if (v != null && type != null && type !== v.initial.type) {
          error('type_mismatch', s.value.path, `variable '${v.name}' holds ${withArticle(v.initial.type)}, but this value is ${withArticle(type)}`);
        }
        if (s.value.kind === 'literal' && s.value.value.type === 'string' && s.value.value.value.length > LIMITS.MAX_TEXT_LENGTH) {
          error('out_of_range', s.value.path, `text must be at most ${LIMITS.MAX_TEXT_LENGTH} characters`);
        }
        break;
      }
      case 'change_var': {
        const v = variable(s.name, join(s.path, 'name'));
        if (v != null && v.initial.type !== NUMBER) {
          error('type_mismatch', join(s.path, 'name'), `change_var only works on number variables, but '${v.name}' holds ${withArticle(v.initial.type)}`);
        }
        expect(s.delta, NUMBER, 'delta', scope);
        break;
      }
      default:
        break;
    }
  }

  // ---------------------------------------------------------------------------------------- run

  for (const p of script.procedures) body(p.body, { procedure: p, loopDepth: 0 });

  if (script.handlers.length === 0) {
    error('no_events', 'events', 'the script has no event handlers, so nothing would ever run; add an on_start handler');
  }
  const seen = new Map();
  for (const h of script.handlers) {
    const path = h.path;
    const ev = h.event;
    switch (ev.type) {
      case 'on_chat': {
        const triggerPath = join(path, 'trigger');
        problem(checkText(FIELDS.EVENT_TRIGGER, ev.trigger), triggerPath);
        if (javaTrim(ev.trigger).startsWith('/')) {
          error('invalid_text', triggerPath, "chat triggers cannot start with '/': that text is a server command, not chat");
        }
        break;
      }
      case 'on_health_below':
        problem(checkNumber(FIELDS.EVENT_HEALTH, ev.health), join(path, 'health'));
        break;
      case 'on_item_below':
        problem(checkText(FIELDS.EVENT_ITEM, ev.item), join(path, 'item'));
        problem(checkNumber(FIELDS.EVENT_COUNT, ev.count), join(path, 'count'));
        break;
      case 'every':
        problem(checkNumber(FIELDS.EVENT_SECONDS, ev.seconds), join(path, 'seconds'));
        break;
      default:
        break;
    }
    const label = eventLabel(ev);
    const key = label.toLowerCase();
    if (seen.has(key)) {
      error('duplicate_event', path, `another handler (${seen.get(key)}) already listens to ${label}`);
    } else {
      seen.set(key, path);
    }
  }
  for (const h of script.handlers) body(h.body, { procedure: null, loopDepth: 0 });

  // recursion: depth-first search; a call to a procedure that is still on the stack closes a cycle
  const state = new Map();
  const visit = (name, stack) => {
    state.set(name, 1);
    stack.push(name);
    for (const [callee, callPath] of callGraph.get(name) || new Map()) {
      const calleeState = state.get(callee);
      if (calleeState === undefined) {
        visit(callee, stack);
      } else if (calleeState === 1) {
        const cycle = [...stack.slice(stack.indexOf(callee)), callee];
        error('recursion', callPath, `procedure '${callee}' would call itself (${cycle.join(' -> ')}); recursion is not allowed, use a loop instead`);
      }
    }
    stack.pop();
    state.set(name, 2);
  };
  for (const name of procedures.keys()) {
    if (!state.has(name)) visit(name, []);
  }

  for (const p of script.procedures) {
    if (!calledProcedures.has(p.name)) warning('unused_procedure', p.path, `procedure '${p.name}' is never called`);
  }
  for (const v of script.variables) {
    if (!usedVariables.has(v.name)) warning('unused_variable', v.path, `variable '${v.name}' is never used`);
  }
  return out;
}

export function hasErrors(diagnostics) {
  return diagnostics.some((d) => d.severity === 'error');
}

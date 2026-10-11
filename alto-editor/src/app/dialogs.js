// Accessible modal dialogs built on <dialog>: messages, confirmations, prompts and small forms.

let host = null;

function el(tag, attrs = {}, ...children) {
  const e = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (v == null || v === false) continue;
    if (k === 'class') e.className = v;
    else if (k === 'text') e.textContent = v;
    else if (k.startsWith('on')) e.addEventListener(k.slice(2), v);
    else e.setAttribute(k, v === true ? '' : v);
  }
  for (const c of children.flat()) if (c != null) e.append(c);
  return e;
}
export { el };

/**
 * Opens a modal. build(body, close) fills the body and returns {focus?: element}. buttons = [{label, value,
 * primary?, danger?}]. Resolves with the value of the button (or null on Escape). onButton(value) may return
 * false to keep the dialog open (validation).
 */
export function openDialog({ title, build, buttons = [{ label: 'OK', value: true, primary: true }], onButton, wide }) {
  return new Promise((resolve) => {
    if (!host) {
      host = el('dialog', { class: 'modal', 'aria-modal': 'true' });
      document.body.appendChild(host);
    }
    const previous = document.activeElement;
    host.textContent = '';
    host.classList.toggle('wide', Boolean(wide));
    const titleId = 'dialog-title-' + Math.random().toString(36).slice(2, 8);
    host.setAttribute('aria-labelledby', titleId);
    const body = el('div', { class: 'modal-body' });
    const form = el('form', { method: 'dialog', class: 'modal-form' },
      el('h2', { id: titleId, text: title }),
      body);
    // Settle directly instead of waiting for the dialog's close event: some browsers deliver that event late
    // (for example while the page is in the background).
    let settled = false;
    const finish = (value) => {
      if (settled) return;
      settled = true;
      host.onclose = null;
      host.oncancel = null;
      host.onkeydown = null;
      if (host.open) host.close();
      if (previous && previous.focus && document.contains(previous)) {
        try {
          previous.focus();
        } catch (e) {
          // the element may not be focusable any more
        }
      }
      resolve(value);
    };
    const built = build ? build(body, finish) || {} : {};
    const actions = el('div', { class: 'modal-actions' });
    for (const b of buttons) {
      const btn = el('button', {
        type: b.primary ? 'submit' : 'button',
        class: b.primary ? 'primary' : b.danger ? 'danger' : '',
        text: b.label,
      });
      btn.addEventListener('click', (ev) => {
        ev.preventDefault();
        if (onButton && onButton(b.value) === false) return;
        finish(b.value);
      });
      actions.append(btn);
    }
    form.append(actions);
    form.addEventListener('submit', (ev) => {
      ev.preventDefault();
      const primary = buttons.find((b) => b.primary);
      if (!primary) return;
      if (onButton && onButton(primary.value) === false) return;
      finish(primary.value);
    });
    host.append(form);
    host.onclose = () => finish(null);
    host.oncancel = (ev) => {
      ev.preventDefault();
      finish(null);
    };
    host.onkeydown = (ev) => {
      if (ev.key === 'Escape') {
        ev.preventDefault();
        finish(null);
      }
    };
    host.showModal();
    const focus = built.focus || form.querySelector('input, select, textarea, button.primary, button');
    if (focus) focus.focus();
  });
}

export function alertDialog(title, message) {
  return openDialog({
    title,
    build: (body) => {
      for (const line of [].concat(message)) body.append(typeof line === 'string' ? el('p', { text: line }) : line);
    },
  });
}

export async function confirmDialog(title, message, okLabel = 'OK', danger = false) {
  const r = await openDialog({
    title,
    build: (body) => {
      for (const line of [].concat(message)) body.append(typeof line === 'string' ? el('p', { text: line }) : line);
    },
    buttons: [{ label: 'Cancel', value: false }, { label: okLabel, value: true, primary: !danger, danger }],
  });
  return r === true;
}

/** A one-field prompt; validate(text) returns an error message or null. */
export async function promptDialog(title, label, value = '', validate = null) {
  let input;
  let error;
  const r = await openDialog({
    title,
    build: (body) => {
      input = el('input', { type: 'text', value, spellcheck: 'false', autocomplete: 'off' });
      error = el('p', { class: 'field-error', role: 'alert' });
      body.append(el('label', { class: 'field' }, el('span', { text: label }), input), error);
      return { focus: input };
    },
    buttons: [{ label: 'Cancel', value: null }, { label: 'OK', value: 'ok', primary: true }],
    onButton: (v) => {
      if (v !== 'ok' || !validate) return true;
      const problem = validate(input.value);
      error.textContent = problem || '';
      return !problem;
    },
  });
  return r === 'ok' ? input.value : null;
}

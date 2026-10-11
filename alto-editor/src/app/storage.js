// localStorage that never throws: private windows, blocked site data or a full quota just mean "not saved".

const PREFIX = 'alto-editor.';

export function storageGet(key) {
  try {
    return window.localStorage.getItem(PREFIX + key);
  } catch (e) {
    return null;
  }
}

/** Returns true when the value was stored. */
export function storageSet(key, value) {
  try {
    window.localStorage.setItem(PREFIX + key, value);
    return true;
  } catch (e) {
    return false;
  }
}

export function storageRemove(key) {
  try {
    window.localStorage.removeItem(PREFIX + key);
  } catch (e) {
    // nothing stored, nothing to remove
  }
}

export function storageAvailable() {
  try {
    const k = PREFIX + 'probe';
    window.localStorage.setItem(k, '1');
    window.localStorage.removeItem(k);
    return true;
  } catch (e) {
    return false;
  }
}

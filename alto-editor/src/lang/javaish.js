// Small helpers that reproduce Java behaviour the Alto validator relies on (number display, Double.toString,
// String.trim / isBlank, "did you mean" hints), so messages and canonical JSON match the game exactly.

/** Java's String.trim(): removes code units <= U+0020 at both ends. */
export function javaTrim(s) {
  let start = 0;
  let end = s.length;
  while (start < end && s.charCodeAt(start) <= 0x20) start++;
  while (end > start && s.charCodeAt(end - 1) <= 0x20) end--;
  return s.substring(start, end);
}

/** Character.isWhitespace for one UTF-16 code unit. */
function javaIsWhitespace(c) {
  if (c === 0x09 || c === 0x0a || c === 0x0b || c === 0x0c || c === 0x0d) return true;
  if (c >= 0x1c && c <= 0x1f) return true;
  if (c === 0x20 || c === 0x1680) return true;
  if (c >= 0x2000 && c <= 0x2006) return true; // U+2007 (figure space) is non-breaking
  if (c >= 0x2008 && c <= 0x200a) return true;
  return c === 0x2028 || c === 0x2029 || c === 0x205f || c === 0x3000; // U+00A0, U+202F are non-breaking
}

/** Java's String.isBlank(). */
export function javaIsBlank(s) {
  for (let i = 0; i < s.length; i++) {
    if (!javaIsWhitespace(s.charCodeAt(i))) return false;
  }
  return true;
}

export function isWhole(v) {
  return Number.isFinite(v) && v === Math.round(v);
}

/** Java's Double.toString (JDK 19+: shortest representation that reads back as the same double). */
export function javaDoubleToString(v) {
  if (Number.isNaN(v)) return 'NaN';
  if (v === Infinity) return 'Infinity';
  if (v === -Infinity) return '-Infinity';
  if (v === 0) return Object.is(v, -0) ? '-0.0' : '0.0';
  const sign = v < 0 ? '-' : '';
  const a = Math.abs(v);
  const [mantissa, expText] = a.toExponential().split('e');
  const digits = mantissa.replace('.', '');
  const exp = Number(expText);
  if (a >= 1e-3 && a < 1e7) {
    // plain notation with at least one digit after the point
    let intPart;
    let fracPart;
    if (exp >= 0) {
      const padded = digits.padEnd(exp + 1, '0');
      intPart = padded.slice(0, exp + 1);
      fracPart = padded.slice(exp + 1);
    } else {
      intPart = '0';
      fracPart = '0'.repeat(-exp - 1) + digits;
    }
    return sign + intPart + '.' + (fracPart.length ? fracPart : '0');
  }
  return sign + digits[0] + '.' + (digits.length > 1 ? digits.slice(1) : '0') + 'E' + exp;
}

/**
 * Value.formatNumber: whole numbers without a fraction, others rounded to 12 significant digits
 * (BigDecimal HALF_UP, trailing zeros stripped, plain notation).
 */
export function formatNumber(v) {
  if (isWhole(v) && Math.abs(v) < 1e15) {
    return String(Math.trunc(v) || 0);
  }
  if (!Number.isFinite(v) || Math.abs(v) >= 1e15 || Math.abs(v) < 1e-6) {
    return javaDoubleToString(v);
  }
  const sign = v < 0 ? '-' : '';
  const [mantissa, expText] = Math.abs(v).toExponential(11).split('e');
  let digits = mantissa.replace('.', '');
  const exp = Number(expText);
  digits = digits.replace(/0+$/, '');
  if (digits === '') digits = '0';
  let out;
  if (exp >= 0) {
    const padded = digits.padEnd(exp + 1, '0');
    const intPart = padded.slice(0, exp + 1);
    const frac = padded.slice(exp + 1);
    out = frac ? intPart + '.' + frac : intPart;
  } else {
    out = '0.' + '0'.repeat(-exp - 1) + digits;
  }
  return sign + out;
}

/** Suggestions.distance: optimal string alignment distance (adjacent swaps count as one edit). */
export function editDistance(a, b) {
  const d = [];
  for (let i = 0; i <= a.length; i++) {
    d.push(new Array(b.length + 1).fill(0));
    d[i][0] = i;
  }
  for (let j = 0; j <= b.length; j++) d[0][j] = j;
  for (let i = 1; i <= a.length; i++) {
    for (let j = 1; j <= b.length; j++) {
      const cost = a[i - 1] === b[j - 1] ? 0 : 1;
      d[i][j] = Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost);
      if (i > 1 && j > 1 && a[i - 1] === b[j - 2] && a[i - 2] === b[j - 1]) {
        d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
      }
    }
  }
  return d[a.length][b.length];
}

/** Suggestions.closest: the first best candidate within distance 2 (1 for words of up to 4 characters). */
export function closest(typed, candidates) {
  if (typed == null || typed === '') return null;
  const limit = typed.length <= 4 ? 1 : 2;
  let best = null;
  let bestDistance = Infinity;
  for (const candidate of candidates) {
    const dist = editDistance(typed, candidate);
    if (dist <= limit && dist < bestDistance) {
      best = candidate;
      bestDistance = dist;
    }
  }
  return best;
}

/** Suggestions.hint: " (did you mean 'craft'?)" or "". */
export function hint(typed, candidates) {
  const best = closest(typed, candidates);
  return best == null ? '' : ` (did you mean '${best}'?)`;
}

/** Java's natural String order (UTF-16 code units). */
export function compareJavaStrings(a, b) {
  return a < b ? -1 : a > b ? 1 : 0;
}

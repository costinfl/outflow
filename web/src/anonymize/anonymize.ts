/*
 * In-browser port of tools/Anonymize.java. Same rules, same order, same seed → byte-identical output
 * (proven by shared golden files, see test/anonymize.test.mjs and AnonymizeToolTest). Runs entirely in the browser:
 * no network, no storage. Self-contained on purpose (no imports), so Node can run it in tests.
 */

export const FAKE_BANK = 'ANON'

export interface AnonymizeOptions {
  /** Private phrase; same seed → same fakes across files and runs. Random when empty. */
  seed?: string
  /** People to replace, one per entry ("First Last" is also matched as "Last First"). */
  names?: string[]
  /** Force a charset; detected otherwise (UTF-8, else windows-1250). */
  encoding?: string
}

export interface AnonymizeResult {
  bytes: Uint8Array
  text: string
  encoding: string
  seedWasRandom: boolean
  /** kind → number of replacements */
  counts: Record<string, number>
  /** kind → up to 5 "partial original  ->  fake" lines */
  examples: Record<string, string[]>
  namesLookedFor: number
  /** People found in Beneficiar / Ordonator / Platitor fields and replaced everywhere */
  detectedNames: number
  /** Values of those fields kept as organisations: check none is a person */
  keptCounterparties: string[]
  /** Things kept that deserve a look */
  leftovers: string[]
  /** Lines that look like transfers: check them for names of people */
  transferLines: string[]
}

// Java's \s is ASCII whitespace only; JS's is Unicode. Spelled out to behave identically.
const WS = '[ \\t\\n\\x0B\\f\\r]'

const IBAN = /\b([A-Z]{2}\d{2}(?: ?[A-Z0-9]){11,30})\b/g
const PAN = /(?<![\dA-Za-z])(\d{4}[ -]?\d{4}[ -]?\d{4}[ -]?\d{1,7})(?!\d)/g
const MASK_DIGITS = /(?<!\d)((?:\d{0,6})[*Xx•]{2,}[ *Xx•]*)(\d{4})(?!\d)/g
const CARD_DIGITS = new RegExp(`(\\bcard${WS}*(?:nr\\.?|no\\.?)?${WS}*)(\\d{4})(?![ -]?\\d)`, 'gi')
const CNP = /(?<!\d)([1-8]\d{12})(?!\d)/g
const EMAIL = /[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/g
const PHONE = new RegExp(`(?<![\\d+])(?:\\+40|0040|0)${WS}?7\\d{2}(?:${WS}|[.-])?\\d{3}(?:${WS}|[.-])?\\d{3}(?!\\d)`, 'g')
const LONG_DIGITS = /(?<![\p{L}\d.,])(\d{10,})(?![\d.,]\d)/gu
const MOBILE = /^(?:0040|40|0)?7\d{8}$/
const HOLDER =
  /^([ \t"]*(?:titular(?: cont)?|nume(?: client)?|client|account holder|holder|name)[ \t"]*[:;,\t]+[ \t"]*)([^;,\t"\r\n]+)/gim
/** Bank fields that hold who paid or who was paid: a person's name unless it looks like an organisation. */
const NAME_FIELD =
  /\b(?:beneficiar|ordonator|pl[aAăĂ]titor|nume beneficiar|nume pl[aAăĂ]titor|nume ordonator|payee|payer)[ \t]*:[ \t]*([^,;"\t\r\n]+)/gi
const ORGANISATION =
  /\b(?:SRL|SA|SCS|SNC|SCA|LTD|LIMITED|GMBH|AG|BV|NV|INC|LLC|PLC|SAS|SPA|IFN|BANK|BANCA|ROMANIA|ASOCIATIA|FUNDATIA|PRIMARIA|DIRECTIA|MINISTERUL|UNIVERSITATEA|SCOALA|LICEUL|GRADINITA|SPITALUL|CLINICA|CENTRUL|MEDICAL|HOSPITAL|ASIGURARI|INSURANCE|TRADING|SYSTEMS|SERVICES|SERV|GROUP|HOLDING|COMPANY|INTERNATIONAL|TREZORERIA|ADMINISTRATIA|ANAF|CNAS)\b/i
const PERSON_LIKE = /^[\p{L}'-]+(?:[ .][\p{L}'-]+){1,4}$/u
/**
 * Free-text memos typed by people (transfer details, messages): anything can be in them, first names included.
 * A quoted cell is taken up to its closing quote, so a comma inside a memo cannot leak the rest of it.
 */
const MEMO =
  /("(?:detalii|details|mesaj|message|explicatii)[ \t]*:[ \t]*)([^"\r\n]*)(?=")|(\b(?:detalii|details|mesaj|message|explicatii)[ \t]*:[ \t]*)([^,;"\t\r\n]*)/gi
/**
 * A long memo wraps onto following lines without a "Key:" label (ING). Each such line after a scrubbed memo is
 * blanked; the ING parser skips detail lines with an empty text cell.
 */
const MEMO_WRAP =
  /((?:detalii|details|mesaj|message|explicatii)[ \t]*:[ \t]*NOTE_\d+[^\r\n]*\r?\n(?:,{3,}\r?\n)*,,,)(?:"[^"\r\n]*"|[^,;:"\r\n]+(?=[,;\r\n]|$))/gi
/** Memos the bank writes itself, kept: ING's "Suma tranzactiei: 5.95 RON"; and an earlier run's NOTE_n. */
const SYSTEM_MEMO = /^(?:suma tranzactiei: [0-9.,]+ [A-Z]{3}|NOTE_\d+)$/i
const TRANSFERISH = /transfer|catre|către|de la|beneficiar|ordonator|platitor|plătitor|p2p|revolut|incasare|încasare/i

/** Java's String.strip(): Character.isWhitespace, which (unlike JS trim) keeps no-break spaces. */
const JAVA_WS = '[\\t\\n\\x0B\\f\\r\\x1C-\\x1F \\u1680\\u2000-\\u2006\\u2008-\\u200A\\u2028\\u2029\\u205F\\u3000]'
const JAVA_WS_START = new RegExp(`^${JAVA_WS}+`, 'u')
const JAVA_WS_END = new RegExp(`${JAVA_WS}+$`, 'u')

function strip(s: string): string {
  return s.replace(JAVA_WS_START, '').replace(JAVA_WS_END, '')
}

export function mod97(s: string): number {
  let digits = ''
  for (const ch of s) digits += /\d/.test(ch) ? ch : String(ch.charCodeAt(0) - 65 + 10)
  return Number(BigInt(digits) % 97n)
}

export function ibanValid(text: string): boolean {
  const c = text.replace(/ /g, '').toUpperCase()
  return /^[A-Z]{2}\d{2}[A-Z0-9]{11,30}$/.test(c) && mod97(c.substring(4) + c.substring(0, 4)) === 1
}

function ibanFind(text: string): string | null {
  const m = /[A-Z]{2}\d{2}[A-Z0-9 ]{11,}/.exec(text.toUpperCase())
  return m && ibanValid(m[0]) ? m[0] : null
}

export function luhn(digits: string): boolean {
  let sum = 0
  for (let i = 0; i < digits.length; i++) {
    let d = digits.charCodeAt(digits.length - 1 - i) - 48
    if (i % 2 === 1) d = d * 2 > 9 ? d * 2 - 9 : d * 2
    sum += d
  }
  return sum % 10 === 0
}

export function cnpValid(cnp: string): boolean {
  if (cnp.length !== 13) return false // a CNP has exactly 13 digits (long references of 10+ digits are checked too)
  const w = '279146358279'
  let sum = 0
  for (let i = 0; i < 12; i++) sum += (cnp.charCodeAt(i) - 48) * (w.charCodeAt(i) - 48)
  return (sum % 11 === 10 ? 1 : sum % 11) === cnp.charCodeAt(12) - 48
}

export function fold(s: string): string {
  return s.normalize('NFD').replace(/\p{M}+/gu, '').toUpperCase()
}

function escapeRegex(c: string): string {
  return c.replace(/[.*+?^${}()|[\]\\/]/g, '\\$&')
}

/** A name as whole words, case- and diacritic-insensitive, any of [space . , -] between words. */
export function namePattern(name: string): RegExp {
  let p = '(?<![\\p{L}\\d])'
  const words = strip(name).split(/[ \t\n\x0B\f\r]+/)
  words.forEach((word, w) => {
    if (w > 0) p += `(?:${WS}|[.,-])+`
    for (const c of fold(word)) {
      p +=
        c === 'A' ? '[AĂÂaăâ]'
        : c === 'I' ? '[IÎiî]'
        : c === 'S' ? '[SȘŞsșş]'
        : c === 'T' ? '[TȚŢtțţ]'
        : /\p{L}/u.test(c) ? `[${c}${c.toLowerCase()}]`
        : escapeRegex(c)
    }
  })
  return new RegExp(p + '(?![\\p{L}\\d])', 'gu')
}

/** Enough of the original to recognise it in the report, not enough to leak it. */
function peek(original: string): string {
  const s = strip(original)
  return s.length <= 6 ? s.charAt(0) + '...' : s.substring(0, 3) + '...' + s.substring(s.length - 2)
}

// ---- encoding -----------------------------------------------------------------------------------------------------

export function detectEncoding(bytes: Uint8Array): string {
  try {
    new TextDecoder('utf-8', { fatal: true }).decode(bytes)
    return 'utf-8'
  } catch {
    return 'windows-1250' // older Romanian bank exports
  }
}

function decode(bytes: Uint8Array, encoding: string): string {
  // ignoreBOM keeps a leading BOM in the text, so it is written back exactly like the Java tool does.
  return new TextDecoder(encoding, { fatal: true, ignoreBOM: true }).decode(bytes)
}

function encode(text: string, encoding: string): Uint8Array {
  if (/^utf-?8$/i.test(encoding)) return new TextEncoder().encode(text)
  // Single-byte charsets: build the reverse table from the browser's own decoder.
  const table = new Map<string, number>()
  const decoder = new TextDecoder(encoding)
  for (let b = 0; b < 256; b++) table.set(decoder.decode(new Uint8Array([b])), b)
  const out = new Uint8Array(text.length)
  let i = 0
  for (const ch of text) {
    const b = table.get(ch)
    if (b === undefined) throw new Error(`Character ${JSON.stringify(ch)} cannot be written as ${encoding}`)
    out[i++] = b
  }
  return out.subarray(0, i)
}

// ---- the anonymizer -----------------------------------------------------------------------------------------------

type Replacer = (m: RegExpExecArray) => Promise<string | null> | string | null

class Anonymizer {
  readonly counts: Record<string, number> = {}
  readonly examples: Record<string, string[]> = {}
  private readonly personOf = new Map<string, string>()
  private readonly emailOf = new Map<string, string>()
  private readonly noteOf = new Map<string, string>()
  readonly keptCounterparties: string[] = []
  detectedNames = 0
  private readonly names: string[]
  private readonly key: CryptoKey

  private constructor(key: CryptoKey, names: string[]) {
    this.key = key
    this.names = [...names]
  }

  static async create(seed: string, names: string[]): Promise<Anonymizer> {
    const key = await crypto.subtle.importKey('raw', new TextEncoder().encode(seed), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign'])
    return new Anonymizer(key, names)
  }

  get namesLookedFor(): number {
    return this.personOf.size
  }

  private async hmacDecimal(text: string): Promise<string> {
    const h = new Uint8Array(await crypto.subtle.sign('HMAC', this.key, new TextEncoder().encode(text)))
    let hex = ''
    for (const b of h) hex += b.toString(16).padStart(2, '0')
    return BigInt('0x' + hex).toString(10)
  }

  /** Deterministic fake digits (HMAC with the private seed), identical to the Java tool's. */
  async digits(kind: string, original: string, length: number): Promise<string> {
    let d = await this.hmacDecimal(`${kind}|${original}`)
    while (d.length < length) d += await this.hmacDecimal(d)
    return d.substring(0, length)
  }

  async fakeIban(original: string): Promise<string> {
    const compact = original.replace(/ /g, '').toUpperCase()
    const country = compact.substring(0, 2)
    const bban = FAKE_BANK + (await this.digits('iban', compact, compact.length - 8))
    const check = 98 - mod97(bban + country + '00')
    const fake = country + String(check).padStart(2, '0') + bban
    return original.includes(' ') ? fake.replace(/(.{4})(?!$)/g, '$1 ') : fake
  }

  private async replace(text: string, pattern: RegExp, kind: string, replacer: Replacer): Promise<string> {
    const re = new RegExp(pattern.source, pattern.flags.includes('g') ? pattern.flags : pattern.flags + 'g')
    let out = ''
    let last = 0
    for (const m of text.matchAll(re)) {
      const r = await replacer(m as RegExpExecArray)
      out += text.substring(last, m.index)
      last = m.index + m[0].length
      if (r == null || r === m[0]) {
        out += m[0]
        continue
      }
      this.counts[kind] = (this.counts[kind] ?? 0) + 1
      const ex = (this.examples[kind] ??= [])
      const shown = `${peek(m[0])}  ->  ${strip(r)}`
      if (ex.length < 5 && !ex.includes(shown)) ex.push(shown)
      out += r
    }
    return out + text.substring(last)
  }

  async run(text: string): Promise<string> {
    let s = text
    for (const h of s.matchAll(HOLDER)) {
      const name = strip(h[2]!)
      if (name && ibanFind(name) == null && !this.names.some((n) => n.toLowerCase() === name.toLowerCase())) {
        this.names.unshift(name)
      }
    }
    // Counterparty fields: people are replaced like listed names; organisations are kept and reported.
    for (const f of s.matchAll(NAME_FIELD)) {
      const value = strip(f[1]!)
      if (!value || /^PERSON_\d+$/.test(value)) continue
      const organisation = ORGANISATION.test(value.replace(/\./g, ''))
      if (!organisation && PERSON_LIKE.test(value)) {
        if (!this.names.some((n) => n.toLowerCase() === value.toLowerCase())) {
          this.names.push(value)
          this.detectedNames++
        }
      } else if (!this.keptCounterparties.includes(value)) {
        this.keptCounterparties.push(value)
      }
    }
    s = await this.replace(s, MEMO, 'memo', (m) => {
      const prefix = m[1] !== undefined ? m[1] : m[3]!
      const value = strip(m[1] !== undefined ? m[2]! : m[4]!)
      if (!value || SYSTEM_MEMO.test(value)) return null
      const k = fold(value)
      if (!this.noteOf.has(k)) this.noteOf.set(k, `NOTE_${this.noteOf.size + 1}`)
      return prefix + this.noteOf.get(k)!
    })
    for (let before: string | null = null; s !== before; ) {
      before = s
      s = await this.replace(s, MEMO_WRAP, 'memo', (m) => m[1]!)
    }
    s = await this.replace(s, IBAN, 'IBAN', (m) => (ibanValid(m[1]!) ? this.fakeIban(m[1]!) : null))
    s = await this.replace(s, PAN, 'card number', async (m) => {
      const digits = m[1]!.replace(/[ -]/g, '')
      if (digits.length < 13 || digits.length > 19 || !luhn(digits)) return null
      const fake = await this.digits('pan', digits, digits.length)
      let i = 0
      return [...m[1]!].map((c) => (/\d/.test(c) ? fake[i++] : c)).join('')
    })
    s = await this.replace(s, MASK_DIGITS, 'card digits', async (m) => m[1]! + (await this.digits('card4', m[2]!, 4)))
    s = await this.replace(s, CARD_DIGITS, 'card digits', async (m) => m[1]! + (await this.digits('card4', m[2]!, 4)))
    // Before CNPs and phones, and skipping their shapes, so no fake is ever replaced twice.
    s = await this.replace(s, LONG_DIGITS, 'long reference', async (m) =>
      cnpValid(m[1]!) || MOBILE.test(m[1]!) ? null : this.digits('ref', m[1]!, m[1]!.length),
    )
    s = await this.replace(s, CNP, 'CNP', async (m) => (cnpValid(m[1]!) ? '0' + (await this.digits('cnp', m[1]!, 12)) : null))
    s = await this.replace(s, EMAIL, 'email', (m) => {
      const e = m[0].toLowerCase()
      if (!this.emailOf.has(e)) this.emailOf.set(e, `person${this.emailOf.size + 1}@example.invalid`)
      return this.emailOf.get(e)!
    })
    s = await this.replace(s, PHONE, 'phone', async (m) => '0700' + (await this.digits('phone', m[0].replace(/\D/g, ''), 6)))
    for (const name of [...this.names]) {
      const k = fold(name)
      if (!this.personOf.has(k)) this.personOf.set(k, `PERSON_${this.personOf.size + 1}`)
      const person = this.personOf.get(k)!
      s = await this.replace(s, namePattern(name), 'name', () => person)
      const words = strip(name).split(/[ \t\n\x0B\f\r]+/)
      if (words.length === 2) s = await this.replace(s, namePattern(`${words[1]} ${words[0]}`), 'name', () => person)
    }
    return s
  }
}

export async function anonymize(input: Uint8Array, options: AnonymizeOptions = {}): Promise<AnonymizeResult> {
  const encoding = options.encoding ?? detectEncoding(input)
  const text = decode(input, encoding)
  const seedWasRandom = !options.seed
  let seed = options.seed ?? ''
  if (seedWasRandom) {
    const r = crypto.getRandomValues(new Uint8Array(24))
    seed = [...r].map((b) => b.toString(16).padStart(2, '0')).join('')
  }
  const names = (options.names ?? []).map(strip).filter((n) => n && !n.startsWith('#'))
  const a = await Anonymizer.create(seed, names)
  const output = await a.run(text)

  const leftovers: string[] = []
  for (const m of output.matchAll(IBAN)) {
    if (m[1]!.replace(/ /g, '').substring(4, 8) !== FAKE_BANK) leftovers.push(`IBAN-like text kept (checksum invalid?): ${peek(m[1]!)}`)
  }
  const digitRuns = [...output.matchAll(/(?<![\d.,])\d{6,9}(?![\d.,]\d)/g)].length
  if (digitRuns > 0) leftovers.push(`${digitRuns} digit runs of 6-9 digits kept (store/terminal/invoice numbers?)`)
  const transferLines = output.split(/\r\n|\r|\n/).filter((l) => TRANSFERISH.test(l)).slice(0, 40)

  return {
    bytes: encode(output, encoding),
    text: output,
    encoding,
    seedWasRandom,
    counts: a.counts,
    examples: a.examples,
    namesLookedFor: a.namesLookedFor,
    detectedNames: a.detectedNames,
    keptCounterparties: [...a.keptCounterparties].sort(),
    leftovers,
    transferLines,
  }
}

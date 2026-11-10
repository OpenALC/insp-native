export interface RichMark {
  type: string;
  start: number;
  end: number;
  scale?: number;
}

export function markdownRich(source: string): RichDocument {
  const output: string[] = [];
  const marks: RichMark[] = [];
  const wrappers: string[] = ['**', '~~', '<u>', '<mark>', '==', '*'];
  const types: string[] = ['bold', 'strike', 'underline', 'highlight', 'highlight', 'italic'];
  const lines = source.replace(/\r\n/g, '\n').split('\n');
  lines.forEach((raw: string, lineIndex: number): void => {
    const heading = raw.match(/^#{1,3}\s+/);
    const line = heading === null ? raw : raw.slice(heading[0].length);
    const lineStart = output.length;
    let index = 0;
    while (index < line.length) {
      let matched = false;
      for (let kind = 0; kind < wrappers.length; kind++) {
        const open = wrappers[kind];
        if (!line.startsWith(open, index)) continue;
        const close = open === '<u>' ? '</u>' : (open === '<mark>' ? '</mark>' : open);
        const end = line.indexOf(close, index + open.length);
        if (end < index + open.length) continue;
        const start = output.length;
        output.push(...line.slice(index + open.length, end).split(''));
        marks.push({ type: types[kind], start: start, end: output.length });
        index = end + close.length;
        matched = true;
        break;
      }
      if (!matched) { output.push(line[index]); index++; }
    }
    if (heading !== null) {
      marks.push({ type: 'heading', start: lineStart, end: output.length, scale: 1.35 });
      marks.push({ type: 'bold', start: lineStart, end: output.length });
    }
    if (lineIndex < lines.length - 1) output.push('\n');
  });
  return { version: 1, text: output.join(''), spans: marks };
}

export interface RichDocument {
  version: number;
  text: string;
  spans: RichMark[];
}

export function readRich(body: string): RichDocument {
  if (body.startsWith('{')) return JSON.parse(body) as RichDocument;
  return { version: 1, text: body, spans: [] };
}

export function richSegments(document: RichDocument): number[] {
  const points: number[] = [0, document.text.length];
  document.spans.forEach((mark: RichMark): void => {
    points.push(mark.start, mark.end);
  });
  points.sort((a: number, b: number): number => a - b);
  return points.filter((point: number, index: number): boolean => index === 0 || point !== points[index - 1]);
}

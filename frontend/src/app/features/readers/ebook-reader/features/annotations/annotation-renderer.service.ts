import {Service} from '@angular/core';
import {defer, from, Observable, of} from 'rxjs';
import {AnnotationStyle} from '../../../../../shared/service/annotation.service';

export type {AnnotationStyle};

export interface Annotation {
  value: string;
  color?: string;
  style?: AnnotationStyle;
}

/** A note's passage, drawn in the text as a highlight with an underline. */
export interface NoteMark {
  value: string;
  color?: string;
}

export type RenderedAnnotationStyle = AnnotationStyle | 'note';

const DEFAULT_NOTE_COLOR = '#FFC107';

interface AnnotationView {
  addAnnotation(annotation: { value: string }): Promise<{ index: number; label: string } | undefined> | void;
  deleteAnnotation(annotation: { value: string }): Promise<void>;
  showAnnotation(annotation: { value: string }): Promise<void>;
}

@Service()
export class ReaderAnnotationService {
  private readonly annotationStyles = new Map<string, { color: string; style: AnnotationStyle }>();
  private allAnnotations: Annotation[] = [];
  private readonly noteMarks = new Map<string, string>();

  private isRenderableAnnotationValue(value: string | null | undefined): value is string {
    const trimmed = value?.trim();
    return !!trimmed && /^epubcfi\(/i.test(trimmed);
  }

  addAnnotation(view: AnnotationView | null | undefined, annotation: Annotation): Observable<{ index: number; label: string } | undefined> {
    if (!view || !this.isRenderableAnnotationValue(annotation.value)) return of(undefined);

    const style = annotation.style || 'highlight';
    const color = annotation.color || '#FACC15';

    this.annotationStyles.set(annotation.value, {color, style});

    const existingIndex = this.allAnnotations.findIndex(a => a.value === annotation.value);
    if (existingIndex >= 0) {
      this.allAnnotations[existingIndex] = annotation;
    } else {
      this.allAnnotations.push(annotation);
    }

    return defer(() => from(view.addAnnotation({value: annotation.value}) as Promise<{ index: number; label: string } | undefined>));
  }

  deleteAnnotation(view: AnnotationView | null | undefined, cfi: string): Observable<void> {
    if (!view || !this.isRenderableAnnotationValue(cfi)) return of(undefined);

    this.annotationStyles.delete(cfi);
    this.allAnnotations = this.allAnnotations.filter(a => a.value !== cfi);

    if (this.noteMarks.has(cfi)) {
      // A note sits on the same passage: redraw it as a note instead of clearing the mark.
      return defer(() => from(Promise.resolve(view.addAnnotation({value: cfi})).then(() => undefined)));
    }

    return defer(() => from(view.deleteAnnotation({value: cfi})));
  }

  showAnnotation(view: AnnotationView | null | undefined, cfi: string): Observable<void> {
    if (!view || !this.isRenderableAnnotationValue(cfi)) return of(undefined);

    return defer(() => from(view.showAnnotation({value: cfi})));
  }

  addAnnotations(view: AnnotationView | null | undefined, annotations: Annotation[]): void {
    annotations.forEach(annotation => {
      if (!this.isRenderableAnnotationValue(annotation.value)) {
        return;
      }

      const style = annotation.style || 'highlight';
      const color = annotation.color || '#FACC15';
      this.annotationStyles.set(annotation.value, {color, style});

      const existingIndex = this.allAnnotations.findIndex(a => a.value === annotation.value);
      if (existingIndex >= 0) {
        this.allAnnotations[existingIndex] = annotation;
      } else {
        this.allAnnotations.push(annotation);
      }

      view?.addAnnotation({value: annotation.value});
    });
  }

  /**
   * Replaces the set of note passages marked in the text. A highlight on the exact same
   * passage keeps its own style; the note mark shows again once that highlight is removed.
   */
  setNoteMarks(view: AnnotationView | null | undefined, marks: NoteMark[]): void {
    const next = new Map<string, string>();
    for (const mark of marks) {
      if (this.isRenderableAnnotationValue(mark.value)) {
        next.set(mark.value, mark.color || DEFAULT_NOTE_COLOR);
      }
    }

    const removed = [...this.noteMarks.keys()].filter(cfi => !next.has(cfi));
    const changed = [...next].filter(([cfi, color]) => this.noteMarks.get(cfi) !== color).map(([cfi]) => cfi);

    this.noteMarks.clear();
    next.forEach((color, cfi) => this.noteMarks.set(cfi, color));

    for (const cfi of removed) {
      if (!this.annotationStyles.has(cfi)) {
        void view?.deleteAnnotation({value: cfi});
      }
    }
    for (const cfi of changed) {
      if (!this.annotationStyles.has(cfi)) {
        void view?.addAnnotation({value: cfi});
      }
    }
  }

  getAnnotationStyle(cfi: string): { color: string; style: RenderedAnnotationStyle } | undefined {
    const noteColor = this.noteMarks.get(cfi);
    return this.annotationStyles.get(cfi) ?? (noteColor ? {color: noteColor, style: 'note'} : undefined);
  }

  getOverlayerDrawFunction(style: RenderedAnnotationStyle): (rects: DOMRectList, options: { color?: string }) => SVGElement {
    const createSVGElement = (tag: string): SVGElement =>
      document.createElementNS('http://www.w3.org/2000/svg', tag);

    switch (style) {
      case 'underline':
        return (rects: DOMRectList, options: { color?: string; width?: number } = {}) => {
          const {color = 'red', width: strokeWidth = 2} = options;
          const g = createSVGElement('g');
          g.setAttribute('fill', color);
          for (const {left, bottom, width} of Array.from(rects)) {
            const el = createSVGElement('rect');
            el.setAttribute('x', String(left));
            el.setAttribute('y', String(bottom - strokeWidth));
            el.setAttribute('height', String(strokeWidth));
            el.setAttribute('width', String(width));
            g.append(el);
          }
          return g;
        };

      case 'strikethrough':
        return (rects: DOMRectList, options: { color?: string; width?: number } = {}) => {
          const {color = 'red', width: strokeWidth = 2} = options;
          const g = createSVGElement('g');
          g.setAttribute('fill', color);
          for (const {left, top, bottom, width} of Array.from(rects)) {
            const el = createSVGElement('rect');
            el.setAttribute('x', String(left));
            el.setAttribute('y', String((top + bottom) / 2));
            el.setAttribute('height', String(strokeWidth));
            el.setAttribute('width', String(width));
            g.append(el);
          }
          return g;
        };

      case 'squiggly':
        return (rects: DOMRectList, options: { color?: string; width?: number } = {}) => {
          const {color = 'red', width: strokeWidth = 2} = options;
          const g = createSVGElement('g');
          g.setAttribute('fill', 'none');
          g.setAttribute('stroke', color);
          g.setAttribute('stroke-width', String(strokeWidth));
          const block = strokeWidth * 1.5;
          for (const {left, bottom, width} of Array.from(rects)) {
            const el = createSVGElement('path');
            const n = Math.round(width / block / 1.5);
            const inline = width / n;
            const ls = Array.from({length: n}, (_, i) =>
              `l${inline} ${i % 2 ? block : -block}`).join('');
            el.setAttribute('d', `M${left} ${bottom}${ls}`);
            g.append(el);
          }
          return g;
        };

      case 'note':
        return (rects: DOMRectList, options: { color?: string; width?: number }) => {
          const {color = DEFAULT_NOTE_COLOR, width: strokeWidth = 2} = options;
          const g = createSVGElement('g');
          g.setAttribute('fill', color);
          for (const {left, top, bottom, height, width} of Array.from(rects)) {
            const fill = createSVGElement('rect');
            fill.setAttribute('x', String(left));
            fill.setAttribute('y', String(top));
            fill.setAttribute('height', String(height));
            fill.setAttribute('width', String(width));
            fill.style.opacity = 'var(--overlayer-highlight-opacity, .3)';
            fill.style.mixBlendMode = 'var(--overlayer-highlight-blend-mode, multiply)';
            const line = createSVGElement('rect');
            line.setAttribute('x', String(left));
            line.setAttribute('y', String(bottom - strokeWidth));
            line.setAttribute('height', String(strokeWidth));
            line.setAttribute('width', String(width));
            g.append(fill, line);
          }
          return g;
        };

      case 'highlight':
      default:
        return (rects: DOMRectList, options: { color?: string } = {}) => {
          const {color = 'yellow'} = options;
          const g = createSVGElement('g');
          g.setAttribute('fill', color);
          (g).style.opacity = 'var(--overlayer-highlight-opacity, .3)';
          (g).style.mixBlendMode = 'var(--overlayer-highlight-blend-mode, multiply)';
          for (const {left, top, height, width} of Array.from(rects)) {
            const el = createSVGElement('rect');
            el.setAttribute('x', String(left));
            el.setAttribute('y', String(top));
            el.setAttribute('height', String(height));
            el.setAttribute('width', String(width));
            g.append(el);
          }
          return g;
        };
    }
  }

  /** Everything to redraw when a section loads: highlights plus note passages. */
  getAllAnnotations(): Annotation[] {
    const noteOnly = [...this.noteMarks.keys()]
      .filter(cfi => !this.annotationStyles.has(cfi))
      .map(cfi => ({value: cfi}));
    return [...this.allAnnotations, ...noteOnly];
  }

  resetAnnotations(): void {
    this.annotationStyles.clear();
    this.allAnnotations = [];
    this.noteMarks.clear();
  }
}

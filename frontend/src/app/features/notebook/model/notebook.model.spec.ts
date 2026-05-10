import {describe, expect, expectTypeOf, it} from 'vitest';

import {NotebookBookOption, NotebookEntry, NotebookPage} from './notebook.model';

describe('notebook.model', () => {
  it('supports notebook entries with annotation metadata', () => {
    const entry: NotebookEntry = {
      id: 5,
      type: 'HIGHLIGHT',
      bookId: 10,
      bookTitle: 'The Left Hand of Darkness',
      text: 'Light is the left hand of darkness',
      note: 'Opening line',
      cfi: 'epubcfi(/6/2)',
      pageNumber: 12,
      createdAt: '2026-03-26T10:00:00Z'
    };

    expect(entry.type).toBe('HIGHLIGHT');
    expectTypeOf(entry.updatedAt).toEqualTypeOf<string | undefined>();
    expect(entry.cfi).toBe('epubcfi(/6/2)');
    expect(entry.pageNumber).toBe(12);
  });

  it('captures paged notebook results and book options', () => {
    const page: NotebookPage = {
      content: [],
      page: {
        totalElements: 0,
        totalPages: 0,
        number: 0,
        size: 25
      }
    };
    const option: NotebookBookOption = {
      bookId: 10,
      bookTitle: 'The Left Hand of Darkness'
    };

    expect(page.page.size).toBe(25);
    expect(option.bookTitle).toContain('Darkness');
  });
});

/**
 * @license
 * Copyright 2022 Google LLC
 * SPDX-License-Identifier: Apache-2.0
 */
import '../../../test/common-test-setup';
import './gr-diff-row';
import {GrDiffRow} from './gr-diff-row';
import {fixture, html, assert} from '@open-wc/testing';
import {GrDiffLine} from '../gr-diff/gr-diff-line';
import {DiffViewMode, GrDiffLineType} from '../../../api/diff';
import {diffModelToken} from '../gr-diff-model/gr-diff-model';
import {testResolver} from '../../../test/common-test-setup';

suite('gr-diff-row test', () => {
  let element: GrDiffRow;

  setup(async () => {
    element = await fixture<GrDiffRow>(html`<gr-diff-row></gr-diff-row>`);
    element.addTableWrapperForTesting = true;
    await element.updateComplete;
  });

  test('both', async () => {
    const line = new GrDiffLine(GrDiffLineType.BOTH, 1, 1);
    line.text = 'lorem ipsum';
    element.left = line;
    element.right = line;
    await element.updateComplete;
    assert.lightDom.equal(
      element,
      /* HTML */ `
        <table>
          <tbody>
            <tr
              aria-labelledby="left-button-1 left-content-1 right-button-1 right-content-1"
              class="diff-row gr-diff side-by-side"
              left-type="both"
              right-type="both"
              tabindex="-1"
            >
              <td class="gr-diff left lineNum" data-value="1">
                <button
                  aria-label="1 unmodified"
                  class="gr-diff left lineNumButton"
                  data-value="1"
                  id="left-button-1"
                  tabindex="-1"
                >
                  1
                </button>
              </td>
              <td class="both content gr-diff left no-intraline-info">
                <div
                  class="contentText gr-diff"
                  data-side="left"
                  id="left-content-1"
                >
                  <gr-diff-text data-side="left"> lorem ipsum </gr-diff-text>
                </div>
                </div>
              </td>
              <td class="gr-diff lineNum right" data-value="1">
                <button
                  aria-label="1 unmodified"
                  class="gr-diff lineNumButton right"
                  data-value="1"
                  id="right-button-1"
                  tabindex="-1"
                >
                  1
                </button>
              </td>
              <td class="both content gr-diff no-intraline-info right">
                <div
                  class="contentText gr-diff"
                  data-side="right"
                  id="right-content-1"
                >
                  <gr-diff-text data-side="right"> lorem ipsum </gr-diff-text>
                </div>
              </td>
            </tr>
            <slot name="post-left-line-1"></slot>
            <slot name="post-right-line-1"></slot>
          </tbody>
        </table>
      `
    );
  });

  test('both unified', async () => {
    const line = new GrDiffLine(GrDiffLineType.BOTH, 1, 1);
    line.text = 'lorem ipsum';
    element.left = line;
    element.right = line;
    const diffModel = testResolver(diffModelToken);
    diffModel.updateState({renderPrefs: {view_mode: DiffViewMode.UNIFIED}});
    element.unifiedDiff = true;
    await element.updateComplete;
    assert.lightDom.equal(
      element,
      /* HTML */ `
        <table>
          <tbody>
            <tr
              aria-labelledby="left-button-1 right-button-1 right-content-1"
              class="both diff-row gr-diff unified"
              tabindex="-1"
            >
              <td class="gr-diff left lineNum" data-value="1">
                <button
                  aria-label="1 unmodified"
                  class="gr-diff left lineNumButton"
                  data-value="1"
                  id="left-button-1"
                  tabindex="-1"
                >
                  1
                </button>
              </td>
              <td class="gr-diff lineNum right" data-value="1">
                <button
                  aria-label="1 unmodified"
                  class="gr-diff lineNumButton right"
                  data-value="1"
                  id="right-button-1"
                  tabindex="-1"
                >
                  1
                </button>
              </td>
              <td class="both content gr-diff no-intraline-info right">
                <div
                  class="contentText gr-diff"
                  data-side="right"
                  id="right-content-1"
                >
                  <gr-diff-text data-side="right"> lorem ipsum </gr-diff-text>
                </div>
              </td>
            </tr>
            <slot name="post-left-line-1"></slot>
            <slot name="post-right-line-1"></slot>
          </tbody>
        </table>
      `
    );
  });

  test('add', async () => {
    const line = new GrDiffLine(GrDiffLineType.ADD, 0, 1);
    line.text = 'lorem ipsum';
    element.left = new GrDiffLine(GrDiffLineType.BLANK);
    element.right = line;
    await element.updateComplete;
    assert.lightDom.equal(
      element,
      /* HTML */ `
        <table>
          <tbody>
            <tr
              aria-labelledby="right-button-1 right-content-1"
              class="diff-row gr-diff side-by-side"
              left-type="blank"
              right-type="add"
              tabindex="-1"
            >
              <td class="blankLineNum gr-diff left"></td>
              <td class="blank gr-diff left no-intraline-info">
                <div class="contentText gr-diff" data-side="left"></div>
              </td>
              <td class="gr-diff lineNum right" data-value="1">
                <button
                  aria-label="1 added"
                  class="gr-diff lineNumButton right"
                  data-value="1"
                  id="right-button-1"
                  tabindex="-1"
                >
                  1
                </button>
              </td>
              <td class="add content gr-diff no-intraline-info right">
                <div
                  class="contentText gr-diff"
                  data-side="right"
                  id="right-content-1"
                >
                  <gr-diff-text data-side="right"> lorem ipsum </gr-diff-text>
                </div>
              </td>
              <slot name="post-right-line-1"></slot>
            </tr>
          </tbody>
        </table>
      `
    );
  });

  test('remove', async () => {
    const line = new GrDiffLine(GrDiffLineType.REMOVE, 1, 0);
    line.text = 'lorem ipsum';
    element.left = line;
    element.right = new GrDiffLine(GrDiffLineType.BLANK);
    await element.updateComplete;
    assert.lightDom.equal(
      element,
      /* HTML */ `
        <table>
          <tbody>
            <tr
              aria-labelledby="left-button-1 left-content-1"
              class="diff-row gr-diff side-by-side"
              left-type="remove"
              right-type="blank"
              tabindex="-1"
            >
              <td class="gr-diff left lineNum" data-value="1">
                <button
                  aria-label="1 removed"
                  class="gr-diff left lineNumButton"
                  data-value="1"
                  id="left-button-1"
                  tabindex="-1"
                >
                  1
                </button>
              </td>
              <td class="content gr-diff left no-intraline-info remove">
                <div
                  class="contentText gr-diff"
                  data-side="left"
                  id="left-content-1"
                >
                  <gr-diff-text data-side="left"> lorem ipsum </gr-diff-text>
                </div>
              </td>
              <td class="blankLineNum gr-diff right"></td>
              <td class="blank gr-diff no-intraline-info right">
                <div class="contentText gr-diff" data-side="right"></div>
              </td>
            </tr>
            <slot name="post-left-line-1"></slot>
          </tbody>
        </table>
      `
    );
  });
});

package io.github.asagnc.sta.agent.browser

import org.json.JSONObject

/**
 * Agent 浏览器注入页面的读取与交互脚本。
 *
 * DOM 遍历保留节点、时间、字段和输出上限，避免网页规模导致 Binder 或模型上下文溢出。
 */
internal object BrowserDomScripts {
    fun wrap(body: String): String =
        """
        (function() {
          var MAX_FIELD_CHARS = 240;
          var MAX_URL_CHARS = 320;
          var MAX_DOCUMENT_CHARS = 400000;
          var MAX_SELECTOR_CHARS = 240;

          function boundedString(value, limit) {
            var max = Math.max(0, Number(limit) || MAX_FIELD_CHARS);
            var text = String(value == null ? '' : value);
            if (text.length > max * 4) text = text.slice(0, max * 4);
            return text.slice(0, max);
          }
          function cleanInline(value, limit) {
            var max = Math.max(0, Number(limit) || MAX_FIELD_CHARS);
            return boundedString(value, max * 4)
              .replace(/[\t\r\n ]+/g, ' ')
              .trim()
              .slice(0, max);
          }
          function cleanBlock(value, limit) {
            var max = Math.max(0, Number(limit) || MAX_DOCUMENT_CHARS);
            return boundedString(value, max * 2)
              .replace(/\r/g, '')
              .replace(/[\t ]+\n/g, '\n')
              .replace(/\n[\t ]+/g, '\n')
              .replace(/[\t ]{2,}/g, ' ')
              .replace(/\n{3,}/g, '\n\n')
              .trim()
              .slice(0, max);
          }
          function visible(element) {
            if (!element || !(element instanceof Element)) return false;
            if (element.tagName && element.tagName.toLowerCase() === 'input' &&
                String(element.getAttribute('type') || '').toLowerCase() === 'hidden') return false;
            var ancestor = element;
            var depth = 0;
            while (ancestor && depth < 40) {
              if (ancestor.hidden || ancestor.hasAttribute('inert') ||
                  ancestor.getAttribute('aria-hidden') === 'true') return false;
              var style = window.getComputedStyle(ancestor);
              if (style.display === 'none' || style.visibility === 'hidden' ||
                  style.visibility === 'collapse' || style.contentVisibility === 'hidden' ||
                  Number(style.opacity || 1) <= 0) return false;
              if ((style.clip && style.clip !== 'auto') ||
                  (style.clipPath && style.clipPath !== 'none')) return false;
              ancestor = ancestor.parentElement;
              depth++;
            }
            if (ancestor) return false;
            var rect = element.getBoundingClientRect();
            var tag = String(element.tagName || '').toLowerCase();
            if (window.getComputedStyle(element).display === 'contents' || tag === 'html' || tag === 'body') {
              return true;
            }
            if (rect.right + window.scrollX <= 0 || rect.bottom + window.scrollY <= 0) return false;
            return rect.width > 0 && rect.height > 0 && element.getClientRects().length > 0;
          }
          function enabled(element) {
            return visible(element) && !element.disabled &&
              element.getAttribute('aria-disabled') !== 'true' &&
              !element.hasAttribute('inert');
          }
          function editable(element) {
            if (!enabled(element) || element.readOnly) return false;
            if (element.isContentEditable) return true;
            var tag = (element.tagName || '').toLowerCase();
            if (tag === 'textarea') return true;
            if (tag !== 'input') return false;
            var type = String(element.getAttribute('type') || 'text').toLowerCase();
            return !['hidden','file','button','submit','reset','image','checkbox','radio'].includes(type);
          }
          function cssEscape(value) {
            if (window.CSS && CSS.escape) return CSS.escape(boundedString(value, 180));
            return boundedString(value, 180).replace(/[^a-zA-Z0-9_-]/g, function(ch) {
              return '\\' + ch.charCodeAt(0).toString(16) + ' ';
            });
          }
          function selectorFor(element) {
            if (!element || !(element instanceof Element)) return null;
            if (element.id) {
              var byId = '#' + cssEscape(element.id);
              try { if (document.querySelectorAll(byId).length === 1) return byId; } catch (_) {}
            }
            var parts = [];
            var node = element;
            var depth = 0;
            while (node && node.nodeType === Node.ELEMENT_NODE && node !== document.body && depth < 20) {
              var part = String(node.tagName || '').toLowerCase();
              var parent = node.parentElement;
              if (!part) break;
              if (parent) {
                var position = 0;
                var count = 0;
                for (var index = 0; index < parent.children.length && index < 2000; index++) {
                  if (parent.children[index].tagName === node.tagName) {
                    count++;
                    if (parent.children[index] === node) position = count;
                  }
                }
                if (count > 1 && position > 0) part += ':nth-of-type(' + position + ')';
              }
              parts.unshift(part);
              var candidate = parts.join(' > ');
              if (candidate.length > MAX_SELECTOR_CHARS) break;
              try { if (document.querySelectorAll(candidate).length === 1) return candidate; } catch (_) {}
              node = parent;
              depth++;
            }
            return boundedString(parts.join(' > '), MAX_SELECTOR_CHARS) || null;
          }
          function absoluteUrl(value) {
            if (!value) return null;
            try {
              var parsed = new URL(boundedString(value, 2048), document.baseURI);
              return boundedString(parsed.href, MAX_URL_CHARS);
            } catch (_) { return null; }
          }
          function collectVisibleText(root, maxChars, nodeLimit, sharedDeadline) {
            var limit = Math.max(0, Math.min(Number(maxChars) || 0, MAX_DOCUMENT_CHARS));
            var maxNodes = Math.max(1, Math.min(Number(nodeLimit) || 1, 12000));
            var parts = [];
            var chars = 0;
            var nodes = 0;
            var truncated = false;
            var deadline = Number(sharedDeadline) || (Date.now() + 500);
            if (!root) return { text: '', truncated: false, nodes: 0 };
            var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
            var item;
            while ((item = walker.nextNode())) {
              nodes++;
              if (nodes > maxNodes || (nodes % 64 === 0 && Date.now() > deadline)) {
                truncated = true;
                break;
              }
              var parent = item.parentElement;
              if (!parent || !visible(parent)) continue;
              var tag = String(parent.tagName || '').toLowerCase();
              if (['script','style','noscript','template','svg','canvas','iframe'].includes(tag)) continue;
              var remaining = limit - chars;
              if (remaining <= 0) {
                truncated = true;
                break;
              }
              var text = cleanInline(item.nodeValue, Math.min(remaining, 2000));
              if (!text) continue;
              parts.push(text);
              chars += text.length + 1;
            }
            return {
              text: cleanBlock(parts.join('\n'), limit),
              truncated: truncated,
              nodes: Math.min(nodes, maxNodes)
            };
          }
          function visibleText(root, maxChars, nodeLimit, sharedDeadline) {
            return collectVisibleText(root, maxChars, nodeLimit, sharedDeadline).text;
          }
          function describe(element, sharedDeadline) {
            var rect = element.getBoundingClientRect();
            return {
              selector: selectorFor(element),
              tag: boundedString((element.tagName || '').toLowerCase(), 32),
              role: cleanInline(element.getAttribute('role'), 48) || null,
              text: visibleText(element, 160, 400, sharedDeadline),
              aria_label: cleanInline(element.getAttribute('aria-label'), 100),
              placeholder: cleanInline(element.getAttribute('placeholder'), 100),
              href: absoluteUrl(element.getAttribute('href')),
              type: cleanInline(element.getAttribute('type'), 32) || null,
              bounds: {
                x: Math.round(rect.left), y: Math.round(rect.top),
                width: Math.round(rect.width), height: Math.round(rect.height)
              }
            };
          }
          function resolveTarget(selector, x, y) {
            var target = null;
            var deadline = Date.now() + 300;
            if (selector) {
              target = document.querySelector(selector);
            } else if (Number.isFinite(x) && Number.isFinite(y)) {
              target = document.elementFromPoint(x, y);
            }
            if (!target) throw new Error('TARGET_NOT_FOUND');
            return target;
          }
          function markdownEscape(value) {
            return boundedString(value, 4000).replace(/([\\`*_[\]<>])/g, '\\${'$'}1');
          }
          function markdownState() {
            return {
              parts: [], remainingNodes: 8000, remainingChars: MAX_DOCUMENT_CHARS,
              visited: 0, deadline: Date.now() + 750, truncated: false
            };
          }
          function consumeNode(state) {
            state.visited++;
            state.remainingNodes--;
            if (state.remainingNodes < 0 || (state.visited % 64 === 0 && Date.now() > state.deadline)) {
              state.truncated = true;
              return false;
            }
            return true;
          }
          function emit(state, value) {
            if (state.remainingChars <= 0) {
              state.truncated = true;
              return;
            }
            var text = String(value || '');
            if (text.length > state.remainingChars) {
              text = text.slice(0, state.remainingChars);
              state.truncated = true;
            }
            state.parts.push(text);
            state.remainingChars -= text.length;
          }
          function renderTable(table, state) {
            var output = [];
            var rows = table.rows || [];
            for (var rowIndex = 0; rowIndex < rows.length && rowIndex < 60; rowIndex++) {
              if (!consumeNode(state)) break;
              var row = [];
              var cells = rows[rowIndex].cells || [];
              for (var cellIndex = 0; cellIndex < cells.length && cellIndex < 12; cellIndex++) {
                if (state.truncated || Date.now() > state.deadline) {
                  state.truncated = true;
                  break;
                }
                row.push(visibleText(cells[cellIndex], 300, 200, state.deadline).replace(/\|/g, '\\|'));
              }
              if (row.length) output.push(row);
            }
            if (!output.length) return;
            var width = Math.max.apply(null, output.map(function(row) { return row.length; }));
            output.forEach(function(row) { while (row.length < width) row.push(''); });
            emit(state, '\n\n| ' + output[0].join(' | ') + ' |\n');
            emit(state, '| ' + output[0].map(function() { return '---'; }).join(' | ') + ' |\n');
            for (var index = 1; index < output.length; index++) {
              emit(state, '| ' + output[index].join(' | ') + ' |\n');
            }
            emit(state, '\n');
          }
          function emitChildren(node, depth, state) {
            for (var index = 0; index < node.childNodes.length; index++) {
              if (state.truncated) break;
              emitMarkdown(node.childNodes[index], depth + 1, state);
            }
          }
          function emitMarkdown(node, depth, state) {
            if (!node || state.truncated || depth > 60 || !consumeNode(state)) return;
            if (node.nodeType === Node.TEXT_NODE) {
              var text = cleanInline(node.nodeValue, 2000);
              if (text) emit(state, markdownEscape(text) + ' ');
              return;
            }
            if (node.nodeType !== Node.ELEMENT_NODE || !visible(node)) return;
            var tag = String(node.tagName || '').toLowerCase();
            if (['script','style','noscript','template','svg','canvas','iframe','nav','form','button','input','textarea','select'].includes(tag)) return;
            if (/^h[1-6]$/.test(tag)) {
              emit(state, '\n\n' + '#'.repeat(Number(tag.substring(1))) + ' ');
              emit(state, markdownEscape(visibleText(node, 2000, 500, state.deadline)) + '\n\n');
              return;
            }
            if (tag === 'br') { emit(state, '\n'); return; }
            if (tag === 'hr') { emit(state, '\n\n---\n\n'); return; }
            if (tag === 'pre') {
              var pre = visibleText(node, 6000, 1200, state.deadline).replace(/```/g, '``\\`');
              if (pre) emit(state, '\n\n```\n' + pre + '\n```\n\n');
              return;
            }
            if (tag === 'code') {
              emit(state, '`' + visibleText(node, 1000, 300, state.deadline).replace(/`/g, '\\`') + '`');
              return;
            }
            if (tag === 'blockquote') {
              var quote = visibleText(node, 5000, 1200, state.deadline);
              if (quote) emit(state, '\n\n' + quote.split('\n').map(function(line) { return '> ' + line; }).join('\n') + '\n\n');
              return;
            }
            if (tag === 'table') { renderTable(node, state); return; }
            if (tag === 'a') {
              var label = visibleText(node, 600, 300, state.deadline) || cleanInline(node.getAttribute('aria-label'), 160);
              var href = absoluteUrl(node.getAttribute('href'));
              if (label) emit(state, href ? '[' + markdownEscape(label) + '](' + href + ')' : markdownEscape(label));
              return;
            }
            if (tag === 'img') {
              var alt = cleanInline(node.getAttribute('alt'), 200);
              if (alt) emit(state, '[图片：' + markdownEscape(alt) + ']');
              return;
            }
            if (tag === 'ul' || tag === 'ol') {
              emit(state, '\n\n');
              var number = 0;
              for (var itemIndex = 0; itemIndex < node.children.length && itemIndex < 200; itemIndex++) {
                if (state.truncated || Date.now() > state.deadline) {
                  state.truncated = true;
                  break;
                }
                var item = node.children[itemIndex];
                if (String(item.tagName || '').toLowerCase() !== 'li' || !visible(item)) continue;
                number++;
                emit(state, tag === 'ol' ? String(number) + '. ' : '- ');
                emitChildren(item, depth + 1, state);
                emit(state, '\n');
              }
              emit(state, '\n');
              return;
            }
            var isBlock = ['p','div','main','article','section','header','footer','aside','figure','figcaption','details','summary','dl','dt','dd'].includes(tag);
            if (isBlock) emit(state, '\n\n');
            emitChildren(node, depth, state);
            if (isBlock) emit(state, '\n\n');
          }
          function readableTarget() {
            var selectors = ['article','main','[role="main"]','.article','.post','.entry-content','.content'];
            var candidates = [];
            var seen = new Set();
            var deadline = Date.now() + 300;
            for (var selectorIndex = 0; selectorIndex < selectors.length && Date.now() <= deadline; selectorIndex++) {
              var matches;
              try { matches = document.querySelectorAll(selectors[selectorIndex]); } catch (_) { continue; }
              for (var index = 0; index < matches.length && index < 40 && candidates.length < 80; index++) {
                var item = matches[index];
                if (visible(item) && !seen.has(item)) {
                  seen.add(item);
                  candidates.push(item);
                }
              }
            }
            var best = null;
            var bestScore = -1;
            for (var candidateIndex = 0; candidateIndex < candidates.length && Date.now() <= deadline; candidateIndex++) {
              var score = visibleText(candidates[candidateIndex], 20000, 1000, deadline).length;
              if (score > bestScore) {
                best = candidates[candidateIndex];
                bestScore = score;
              }
            }
            return best || document.body || document.documentElement;
          }
          try {
            var value = (function() {
              $body
            })();
            return JSON.stringify({ ok: true, value: value === undefined ? null : value });
          } catch (error) {
            return JSON.stringify({
              ok: false,
              error: cleanInline(error && error.message ? error.message : 'SCRIPT_FAILED', 160)
            });
          }
        })();
        """.trimIndent()

    fun readable(offset: Int, maxChars: Int): String =
        """
        var markdownHolder = function (html) {
          var holder = document.createElement('div');
          holder.setAttribute('data-sta-readability', '1');
          holder.style.position = 'fixed';
          holder.style.left = '-10000px';
          holder.style.top = '0';
          holder.innerHTML = html;
          document.body.appendChild(holder);
          return holder;
        };
        // 启发式路径的噪声度量：只统计、不删除任何节点。Readability 能给出正文时用不到它，
        // 那一路已经按文本密度和 class/id 模式做过清理。
        // 只认独立词元（-、_、空白、驼峰边界分隔），所以 download、adaptive 这类含 ad 的普通词不会命中。
        var NOISE_MARK_PATTERN = /(^|[\s_-])(ad|ads|advert|advertorial|advertisement|sponsor|sponsored|promo|promotion|recommend|recommended|related|sidebar|comment|comments|cookie|gdpr|newsletter|subscribe|share|banner|popup|skyscraper|social|footer|header|menu|nav)([\s_-]|$)/i;
        var countNoiseCandidates = function (root) {
          if (!root) return 0;
          var nodes = root.querySelectorAll('[class],[id]');
          var limit = Math.min(nodes.length, 4000);
          var hits = 0;
          for (var index = 0; index < limit; index++) {
            // 先把 relatedPosts、adBanner 这类驼峰拆成词元，否则整词边界会把它们整段漏掉。
            var mark = (String(nodes[index].getAttribute('class') || '') + ' ' +
              String(nodes[index].getAttribute('id') || ''))
              .replace(/([a-z0-9])([A-Z])/g, '${'$'}1 ${'$'}2');
            if (NOISE_MARK_PATTERN.test(mark)) hits++;
          }
          return hits;
        };
        // 链接文本占比：导航、相关推荐、广告位这类容器里几乎全是链接文字，正文则相反。
        var linkDensity = function (root, deadline) {
          if (!root) return 0;
          var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, null);
          var total = 0;
          var linked = 0;
          var seen = 0;
          var node;
          while ((node = walker.nextNode())) {
            seen++;
            if (seen > 6000 || (seen % 128 === 0 && Date.now() > deadline)) break;
            var parent = node.parentElement;
            if (!parent || !visible(parent)) continue;
            var length = String(node.nodeValue || '').replace(/\s+/g, ' ').trim().length;
            if (!length) continue;
            total += length;
            if (parent.closest('a')) linked += length;
          }
          return total > 0 ? Math.round((linked / total) * 1000) / 1000 : 0;
        };
        var article = null;
        try {
          if (typeof Readability === 'function') {
            article = new Readability(document.cloneNode(true), { charThreshold: 140 }).parse();
          }
        } catch (error) { article = null; }
        var fromReadability = !!(article && article.content);
        var extractor = fromReadability ? 'readability' : 'heuristic';
        var target = fromReadability ? markdownHolder(article.content) : readableTarget();
        if (!target || (!fromReadability && !visible(target))) throw new Error('TARGET_NOT_VISIBLE');
        // Readability 的内容放在离屏容器里，而 emitMarkdown 会跳过不可见节点：不先放宽判定，
        // 整棵子树都会被跳过、结果是 0 字符。读完立即还原。
        var visibleBefore = visible;
        if (fromReadability) {
          // 放宽失败也不能让整次提取挂掉，所以包一层 try。
          try {
            visible = function (node) {
              return node === target || (target && target.contains(node)) || visibleBefore(node);
            };
          } catch (error) { visible = visibleBefore; }
        }
        var state = markdownState();
        emitMarkdown(target, 0, state);
        if (fromReadability) {
          visible = visibleBefore;
          if (target && target.parentNode) target.parentNode.removeChild(target);
        }
        var markdown = cleanBlock(state.parts.join(''), MAX_DOCUMENT_CHARS);
        var usedBodyText = false;
        // 兜底：正文装配失手时退回整页可见文本（复用同一套可见性遍历，不直接读渲染文本属性）。宁可粒度粗，也不要交给调用方 0 字符。
        if (!markdown && document.body) {
          var bodyText = cleanBlock(
            visibleText(document.body, MAX_DOCUMENT_CHARS, 8000, Date.now() + 750),
            MAX_DOCUMENT_CHARS
          );
          if (bodyText) {
            markdown = bodyText;
            usedBodyText = true;
            extractor = extractor + '+body-text';
          }
        }
        // 噪声度量只在非 Readability 路径计算：那两路才可能把导航、推广一起倒出来。
        // 放在这里是因为 root 要取真正交付内容的节点——body 兜底时就是 body。
        var linkDensityValue = null;
        var noiseCandidateCount = null;
        if (!fromReadability) {
          var metricRoot = usedBodyText ? document.body : target;
          linkDensityValue = linkDensity(metricRoot, Date.now() + 200);
          noiseCandidateCount = countNoiseCandidates(metricRoot);
        }
        var total = markdown.length;
        var start = Math.min($offset, total);
        var end = Math.min(start + $maxChars, total);
        var result = {
          text: markdown.slice(start, end),
          text_length: total,
          returned_chars: end - start,
          offset: start,
          next_offset: end < total ? end : null,
          truncated: end < total || state.truncated,
          source_truncated: state.truncated,
          visited_nodes: state.visited,
          selector_used: selectorFor(target),
          extractor: extractor,
          link_density: linkDensityValue,
          noise_candidates: noiseCandidateCount,
          language: cleanInline(document.documentElement.lang, 32) || null,
          canonical_url: (function() {
            var item = document.querySelector('link[rel="canonical"]');
            return item ? absoluteUrl(item.getAttribute('href')) : null;
          })()
        };
        return result;
        """.trimIndent()

    fun text(selector: String?, offset: Int, maxChars: Int): String {
        val selectorLiteral = selector?.let(JSONObject::quote) ?: "null"
        return """
        var selector = $selectorLiteral;
        var target = null;
        if (selector) {
          var matches = document.querySelectorAll(selector);
          for (var index = 0; index < matches.length && index < 2000; index++) {
            if (visible(matches[index])) { target = matches[index]; break; }
          }
        } else {
          target = document.body || document.documentElement;
        }
        if (!target || !visible(target)) throw new Error('TARGET_NOT_VISIBLE');
        var collected = collectVisibleText(target, MAX_DOCUMENT_CHARS, 12000);
        var value = collected.text;
        var total = value.length;
        var start = Math.min($offset, total);
        var end = Math.min(start + $maxChars, total);
        return {
          text: value.slice(start, end),
          text_length: total,
          returned_chars: end - start,
          offset: start,
          next_offset: end < total ? end : null,
          truncated: end < total || collected.truncated,
          source_truncated: collected.truncated,
          visited_nodes: collected.nodes,
          selector_used: selector || selectorFor(target)
        };
        """.trimIndent()
    }

    fun findElements(selector: String?): String {
        val selectorLiteral = JSONObject.quote(
            selector ?: "a,button,input,textarea,select,[role=\"button\"],[role=\"link\"],[contenteditable=\"true\"],[tabindex]"
        )
        return """
        var selector = $selectorLiteral;
        var matches = document.querySelectorAll(selector);
        var elements = [];
        var scanned = 0;
        var deadline = Date.now() + 500;
        for (var index = 0; index < matches.length && index < 3000 && elements.length < 16 && Date.now() <= deadline; index++) {
          scanned++;
          elements.push(describe(matches[index], deadline));
        }
        return {
          selector_used: selector,
          element_count: elements.length,
          scanned_elements: scanned,
          truncated: scanned < matches.length,
          elements: elements
        };
        """.trimIndent()
    }

    fun click(selector: String?, x: Int?, y: Int?): String =
        targeted(selector, x, y) +
            """
            target.scrollIntoView({ block: 'center', inline: 'center' });
            var rect = target.getBoundingClientRect();
            var cx = rect.left + rect.width / 2;
            var cy = rect.top + rect.height / 2;
            ['mousemove','mouseover','mousedown','mouseup'].forEach(function(kind) {
              target.dispatchEvent(new MouseEvent(kind, { bubbles: true, cancelable: true, clientX: cx, clientY: cy }));
            });
            target.click();
            return { matched_element: describe(target) };
            """.trimIndent()

    fun type(selector: String?, x: Int?, y: Int?, text: String, submit: Boolean): String =
        targeted(selector, x, y) +
            """
            if (!editable(target)) throw new Error('TARGET_NOT_EDITABLE');
            target.scrollIntoView({ block: 'center', inline: 'center' });
            target.focus();
            var value = ${JSONObject.quote(text)};
            if (target.isContentEditable) {
              target.textContent = value;
            } else {
              var prototype = target.tagName.toLowerCase() === 'textarea' ?
                window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
              var setter = Object.getOwnPropertyDescriptor(prototype, 'value');
              if (setter && setter.set) setter.set.call(target, value); else target.value = value;
            }
            target.dispatchEvent(new InputEvent('input', { bubbles: true, inputType: 'insertText', data: null }));
            target.dispatchEvent(new Event('change', { bubbles: true }));
            if ($submit) {
              var form = target.form || target.closest('form');
              if (form && form.requestSubmit) form.requestSubmit();
              else target.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', bubbles: true }));
            }
            return { matched_element: describe(target), typed_chars: value.length, submitted: $submit };
            """.trimIndent()

    fun scroll(selector: String?, direction: String, amount: Int): String {
        val selectorLiteral = selector?.let(JSONObject::quote) ?: "null"
        return """
        var selector = $selectorLiteral;
        var target = selector ? document.querySelector(selector) : (document.scrollingElement || document.documentElement);
        if (!target || (selector && !visible(target))) throw new Error('TARGET_NOT_VISIBLE');
        var delta = ${if (direction == "up") -amount else amount};
        var documentTarget = target === document.scrollingElement || target === document.documentElement || target === document.body;
        var before = documentTarget ? window.scrollY : target.scrollTop;
        if (documentTarget) window.scrollBy(0, delta); else target.scrollBy(0, delta);
        var after = documentTarget ? window.scrollY : target.scrollTop;
        return {
          selector_used: selector || selectorFor(target),
          direction: ${JSONObject.quote(direction)}, amount: $amount, before: before, after: after
        };
        """.trimIndent()
    }

    fun pageInfo(): String =
        """
        var canonical = document.querySelector('link[rel="canonical"]');
        return {
          viewport_width: window.innerWidth,
          viewport_height: window.innerHeight,
          content_width: Math.min(200000, Math.max(document.body ? document.body.scrollWidth : 0, document.documentElement.scrollWidth)),
          content_height: Math.min(200000, Math.max(document.body ? document.body.scrollHeight : 0, document.documentElement.scrollHeight)),
          scroll_x: window.scrollX || 0,
          scroll_y: window.scrollY || 0,
          language: cleanInline(document.documentElement.lang, 32) || null,
          canonical_url: canonical ? absoluteUrl(canonical.getAttribute('href')) : null
        };
        """.trimIndent()

    fun selectorState(selector: String): String =
        """
        var matches = document.querySelectorAll(${JSONObject.quote(selector)});
        var target = null;
        for (var index = 0; index < matches.length && index < 2000; index++) {
          if (visible(matches[index])) { target = matches[index]; break; }
        }
        return { found: !!target, visible: !!target, enabled: target ? enabled(target) : false };
        """.trimIndent()

    /**
     * 在页面里执行表达式，并把结果写到 window 上的临时键，供调用方轮询读取。
     *
     * 表达式包在 async 函数里，因此可以写 await；返回值只以文本形式保留，超过 maxChars 时截断。
     * 页面跳转会连带这段结果和 JS 上下文一起失效，所以结果落在 window 上，而不是靠 evaluateJavascript 的返回值。
     * 这段代码是 wrap() 的函数体，自己不要再包一层 IIFE：wrap 取到的返回值会变成 undefined。
     */
    fun evaluateScript(
        expression: String,
        resultKey: String,
        maxChars: Int,
        bridgeObjectName: String? = null,
    ): String =
        """
        var key = ${JSONObject.quote(resultKey)};
        var bridge = ${bridgeObjectName?.let { JSONObject.quote(it) } ?: "null"};
        var limit = $maxChars;
        function describe(value) {
          if (value === undefined) return { kind: 'undefined', text: '' };
          if (value === null) return { kind: 'null', text: '' };
          var type = typeof value;
          if (type === 'string') return { kind: 'string', text: value };
          if (type === 'number' || type === 'boolean') return { kind: type, text: String(value) };
          if (type === 'function') {
            return { kind: 'function', text: '[function ' + (value.name || 'anonymous') + ']' };
          }
          if (typeof value.nodeType === 'number' && typeof value.outerHTML === 'string') {
            return { kind: 'element', text: value.outerHTML };
          }
          try {
            var encoded = JSON.stringify(value);
            if (typeof encoded === 'string') {
              return { kind: Array.isArray(value) ? 'array' : 'object', text: encoded };
            }
          } catch (error) {
          }
          return { kind: 'value', text: String(value) };
        }
        function report(payload) {
          if (bridge !== null) {
            var target = window[bridge];
            if (target && typeof target.postMessage === 'function') {
              payload.nonce = key;
              target.postMessage(JSON.stringify(payload));
              return;
            }
          }
          window[key] = JSON.stringify(payload);
        }
        function fail(error) {
          report({ ok: false, error: String(error && error.message ? error.message : error) });
        }
        var pending = null;
        try {
          pending = (async function() { return ($expression); })();
        } catch (error) {
          fail(error);
          return null;
        }
        Promise.resolve(pending).then(function(value) {
          var described = describe(value);
          var truncated = described.text.length > limit;
          report({
            ok: true,
            kind: described.kind,
            text: truncated ? described.text.slice(0, limit) : described.text,
            full_length: described.text.length,
            truncated: truncated
          });
        }, fail);
        return null;
        """.trimIndent()

    /**
     * 页面内取回 blob:/data: 内容并经桥回传。
     *
     * 二进制走 WebMessageListener 的 ArrayBuffer 通道：头部是一段 UTF-8 JSON（前 4 字节大端长度），
     * 后面紧跟原始字节，这样元信息与内容在同一条消息里，不用靠两条消息的先后顺序配对。
     */
    fun blobDownload(url: String, resultKey: String, bridgeObjectName: String): String =
        """
        var key = ${JSONObject.quote(resultKey)};
        var bridge = ${JSONObject.quote(bridgeObjectName)};
        var targetUrl = ${JSONObject.quote(url)};
        function pack(payload, bytes) {
          var header = new TextEncoder().encode(JSON.stringify(payload));
          var total = new Uint8Array(4 + header.length + (bytes ? bytes.byteLength : 0));
          new DataView(total.buffer).setUint32(0, header.length);
          total.set(header, 4);
          if (bytes) total.set(new Uint8Array(bytes), 4 + header.length);
          return total.buffer;
        }
        function send(payload, bytes) {
          var target = window[bridge];
          if (!target || typeof target.postMessage !== 'function') throw new Error('SCRIPT_BRIDGE_MISSING');
          target.postMessage(pack(payload, bytes));
        }
        fetch(targetUrl).then(function(response) {
          if (!response.ok) throw new Error('HTTP ' + response.status);
          var type = response.headers.get('Content-Type') || '';
          return response.arrayBuffer().then(function(buffer) {
            return { type: type, buffer: buffer };
          });
        }).then(function(result) {
          send({ nonce: key, ok: true, mime_type: result.type, size: result.buffer.byteLength }, result.buffer);
        }, function(error) {
          send({ nonce: key, ok: false, error: String(error && error.message ? error.message : error) }, null);
        });
        return null;
        """.trimIndent()

    /** 检查页面里是否真的存在宿主注入的桥对象：它只对安装监听之后创建的文档生效。 */
    fun bridgeAvailable(objectName: String): String =
        """
        var target = window[${JSONObject.quote(objectName)}];
        return { available: !!target && typeof target.postMessage === 'function' };
        """.trimIndent()

    /** 读走 evaluateScript 留在页面上的结果；结果还没落到 window 时返回 done=false。 */
    fun scriptOutcome(resultKey: String): String =
        """
        var key = ${JSONObject.quote(resultKey)};
        if (window[key] === undefined) return { done: false };
        var payload = window[key];
        delete window[key];
        return { done: true, payload: String(payload) };
        """.trimIndent()

    /**
     * 文档开始即执行的脚本：给页面自己发出的 fetch/XHR 加上同一批请求头。
     *
     * 只处理与文档同源的请求：自定义头会让跨源请求从简单请求变成需要预检，硬加容易把页面弄坏。
     * 这是独立注入的脚本（不经 wrap），返回值为空。
     */
    fun documentStartHeaders(headers: Map<String, String>): String {
        val encoded = buildString {
            append('{')
            headers.entries.forEachIndexed { index, entry ->
                if (index > 0) append(',')
                append(JSONObject.quote(entry.key)).append(':').append(JSONObject.quote(entry.value))
            }
            append('}')
        }
        return """
        (function() {
          var HEADERS = $encoded;
          var NAMES = Object.keys(HEADERS);
          if (NAMES.length === 0) return;

          function sameOrigin(url) {
            try {
              return new URL(String(url), document.baseURI).origin === window.location.origin;
            } catch (error) {
              return false;
            }
          }

          function applyTo(headers) {
            for (var index = 0; index < NAMES.length; index++) {
              try {
                headers.set(NAMES[index], HEADERS[NAMES[index]]);
              } catch (error) {
              }
            }
          }

          var originalFetch = window.fetch;
          if (typeof originalFetch === 'function') {
            window.fetch = function(input, init) {
              var url = typeof input === 'string' ? input : (input && input.url) || '';
              if (sameOrigin(url)) {
                var next = init ? Object.assign({}, init) : {};
                var source = next.headers || (input && input.headers) || undefined;
                var headers = new Headers(source);
                applyTo(headers);
                next.headers = headers;
                return originalFetch.call(this, input, next);
              }
              return originalFetch.call(this, input, init);
            };
          }

          var originalOpen = XMLHttpRequest.prototype.open;
          var originalSend = XMLHttpRequest.prototype.send;
          XMLHttpRequest.prototype.open = function(method, url) {
            this.__staHeaderUrl = url;
            return originalOpen.apply(this, arguments);
          };
          XMLHttpRequest.prototype.send = function() {
            if (sameOrigin(this.__staHeaderUrl || '')) {
              for (var index = 0; index < NAMES.length; index++) {
                try {
                  this.setRequestHeader(NAMES[index], HEADERS[NAMES[index]]);
                } catch (error) {
                }
              }
            }
            return originalSend.apply(this, arguments);
          };
        })();
        """.trimIndent()
    }

    private fun targeted(selector: String?, x: Int?, y: Int?): String {
        val selectorLiteral = selector?.let(JSONObject::quote) ?: "null"
        val xLiteral = x?.toString() ?: "null"
        val yLiteral = y?.toString() ?: "null"
        return "var target = resolveTarget($selectorLiteral, $xLiteral, $yLiteral);\n"
    }
}

from __future__ import annotations

from PySide6.QtCore import Qt, Signal
from PySide6.QtGui import QColor, QFont, QTextCharFormat, QTextCursor
from PySide6.QtWidgets import QTextEdit

from ultidraft.domain.manuscript import Manuscript, Sentence


class ReaderView(QTextEdit):
    sentence_clicked = Signal(int)

    def __init__(self, parent=None) -> None:
        super().__init__(parent)
        self.setReadOnly(True)
        self.setFrameShape(QTextEdit.Shape.NoFrame)
        font = QFont("Georgia", 14)
        font.setStyleHint(QFont.StyleHint.Serif)
        self.setFont(font)
        self.setLineWrapMode(QTextEdit.LineWrapMode.WidgetWidth)
        self._ranges: list[tuple[int, int]] = []
        self._current = -1

    def set_manuscript(self, manuscript: Manuscript) -> None:
        self._ranges = []
        self.clear()
        cursor = self.textCursor()
        cursor.beginEditBlock()
        last_chapter = ""
        for sentence in manuscript.sentences:
            start = cursor.position()
            if sentence.kind == "heading":
                if last_chapter:
                    cursor.insertBlock()
                    cursor.insertBlock()
                fmt = QTextCharFormat()
                fmt.setFontFamily("Georgia")
                fmt.setFontPointSize(20)
                fmt.setFontWeight(QFont.Weight.Bold)
                fmt.setForeground(QColor("#f2f2f2"))
                cursor.insertText(sentence.text, fmt)
                cursor.insertBlock()
                cursor.insertBlock()
                last_chapter = sentence.chapter_id
            else:
                fmt = QTextCharFormat()
                fmt.setFontFamily("Georgia")
                fmt.setFontPointSize(14)
                fmt.setForeground(QColor("#c8c8c8" if sentence.kind == "meta" else "#e0e0e0"))
                if sentence.kind == "meta":
                    fmt.setFontItalic(True)
                cursor.insertText(sentence.text, fmt)
                cursor.insertText("  ")
                if sentence.kind == "meta":
                    cursor.insertBlock()
            end = cursor.position()
            self._ranges.append((start, end))
        cursor.endEditBlock()
        self._current = -1
        if manuscript.sentences:
            self.highlight(0)

    def highlight(self, index: int) -> None:
        if index < 0 or index >= len(self._ranges):
            return
        self._current = index
        start, end = self._ranges[index]
        extra = QTextEdit.ExtraSelection()
        extra.cursor = self.textCursor()
        extra.cursor.setPosition(start)
        extra.cursor.setPosition(end, QTextCursor.MoveMode.KeepAnchor)
        fmt = QTextCharFormat()
        fmt.setBackground(QColor("#c9a227"))
        fmt.setForeground(QColor("#1a1a1a"))
        extra.format = fmt
        self.setExtraSelections([extra])
        self.setTextCursor(extra.cursor)
        self.ensureCursorVisible()

    def sentence_at_cursor(self) -> int | None:
        pos = self.cursorForPosition(self.viewport().mapFromGlobal(self.cursor().pos())).position()
        return self.sentence_at_position(pos)

    def sentence_at_position(self, pos: int) -> int | None:
        for index, (start, end) in enumerate(self._ranges):
            if start <= pos < end:
                return index
        return None

    def mouseReleaseEvent(self, event) -> None:
        super().mouseReleaseEvent(event)
        if event.button() != Qt.MouseButton.LeftButton:
            return
        cursor = self.cursorForPosition(event.position().toPoint())
        index = self.sentence_at_position(cursor.position())
        if index is not None:
            self.sentence_clicked.emit(index)

    def current_sentence(self, manuscript: Manuscript) -> Sentence | None:
        return manuscript.sentence_at(self._current)

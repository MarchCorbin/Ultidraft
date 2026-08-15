from __future__ import annotations

from pathlib import Path

from PySide6.QtCore import Qt, QTimer
from PySide6.QtGui import QAction, QActionGroup, QKeySequence, QShortcut
from PySide6.QtWidgets import (
    QDialog,
    QFileDialog,
    QLabel,
    QListWidget,
    QListWidgetItem,
    QMainWindow,
    QMessageBox,
    QSplitter,
    QVBoxLayout,
    QWidget,
)

from ultidraft.domain.export import default_export_path, write_notes_markdown
from ultidraft.domain.lexicon import apply_lexicon
from ultidraft.domain.manuscript import Manuscript, Sentence, load_manuscript
from ultidraft.domain.notes import Note, Sidecar, load_sidecar, save_sidecar, utc_now_iso
from ultidraft.persist.session import Session, load_session, save_session
from ultidraft.tts.engine import SpeechEngine
from ultidraft.tts.voices import DEFAULT_VOICE_ID
from ultidraft.ui.lexicon_dialog import LexiconDialog
from ultidraft.ui.note_dialog import NoteDialog
from ultidraft.ui.notes_panel import NotesPanel
from ultidraft.ui.reader_view import ReaderView
from ultidraft.ui.style import STYLESHEET
from ultidraft.ui.transport import TransportBar


class MainWindow(QMainWindow):
    def __init__(self) -> None:
        super().__init__()
        self.setWindowTitle("Ultidraft")
        self.resize(1280, 800)
        self.setStyleSheet(STYLESHEET)

        self._manuscript: Manuscript | None = None
        self._sidecar: Sidecar | None = None
        self._index = 0
        self._playing = False
        self._speed = 1.0
        self._voice_id = DEFAULT_VOICE_ID
        self._engine = SpeechEngine(self)
        self._engine.finished_utterance.connect(
            self._on_utterance_finished,
            Qt.ConnectionType.QueuedConnection,
        )
        self._engine.preparing.connect(self.statusBar().showMessage)
        self._engine.failed.connect(self._on_voice_failed)
        self._voice_actions: QActionGroup | None = None
        self._span_end = 0
        self._span_parts: tuple[Sentence, ...] = ()
        self._save_timer = QTimer(self)
        self._save_timer.setSingleShot(True)
        self._save_timer.setInterval(400)
        self._save_timer.timeout.connect(self._flush_state)
        self._highlight_timer = QTimer(self)
        self._highlight_timer.setInterval(80)
        self._highlight_timer.timeout.connect(self._sync_span_highlight)

        self._chapters = QListWidget()
        self._chapters.itemClicked.connect(self._on_chapter_clicked)
        chapter_title = QLabel("CHAPTERS")
        chapter_title.setObjectName("paneTitle")
        chapter_pane = QWidget()
        chapter_layout = QVBoxLayout(chapter_pane)
        chapter_layout.setContentsMargins(0, 0, 0, 0)
        chapter_layout.setSpacing(0)
        chapter_layout.addWidget(chapter_title)
        chapter_layout.addWidget(self._chapters)

        self._reader = ReaderView()
        self._reader.sentence_clicked.connect(self._jump_to)
        self._notes = NotesPanel()
        self._notes.note_selected.connect(self._jump_to)
        self._transport = TransportBar()
        self._wire_transport()

        splitter = QSplitter(Qt.Orientation.Horizontal)
        splitter.addWidget(chapter_pane)
        splitter.addWidget(self._reader)
        splitter.addWidget(self._notes)
        splitter.setStretchFactor(0, 0)
        splitter.setStretchFactor(1, 1)
        splitter.setStretchFactor(2, 0)
        splitter.setSizes([220, 760, 300])

        root = QWidget()
        layout = QVBoxLayout(root)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(0)
        layout.addWidget(splitter, 1)
        layout.addWidget(self._transport)
        self.setCentralWidget(root)
        self.statusBar().showMessage("Open a markdown manuscript (Ctrl+O)")

        self._build_menu()
        self._build_shortcuts()
        self._populate_voices()

    def restore_session(self) -> None:
        session = load_session()
        self._speed = session.speed or 1.0
        self._transport.set_speed(self._speed)
        self._engine.set_speed(self._speed)
        if session.voice_id:
            self._apply_voice(session.voice_id, persist=False)
        path = Path(session.last_file) if session.last_file else None
        if path and path.is_file():
            self._open_path(path, session.sentence_index)

    def closeEvent(self, event) -> None:
        self._playing = False
        self._highlight_timer.stop()
        self._engine.stop()
        self._flush_state()
        super().closeEvent(event)

    def _build_menu(self) -> None:
        file_menu = self.menuBar().addMenu("&File")
        open_action = QAction("Openâ€¦", self)
        open_action.setShortcut(QKeySequence.StandardKey.Open)
        open_action.triggered.connect(self._open_dialog)
        export_action = QAction("Export notesâ€¦", self)
        export_action.setShortcut(QKeySequence("Ctrl+E"))
        export_action.triggered.connect(self._export_notes)
        quit_action = QAction("Exit", self)
        quit_action.setShortcut(QKeySequence.StandardKey.Quit)
        quit_action.triggered.connect(self.close)
        file_menu.addAction(open_action)
        file_menu.addAction(export_action)
        file_menu.addSeparator()
        file_menu.addAction(quit_action)

        voice_menu = self.menuBar().addMenu("&Voice")
        self._voice_menu = voice_menu

        help_menu = self.menuBar().addMenu("&Help")
        about = QAction("About Ultidraft", self)
        about.triggered.connect(self._about)
        help_menu.addAction(about)

    def _build_shortcuts(self) -> None:
        QShortcut(QKeySequence(Qt.Key.Key_Space), self, self._toggle_play)
        QShortcut(QKeySequence(Qt.Key.Key_Left), self, self._previous_sentence)
        QShortcut(QKeySequence(Qt.Key.Key_Right), self, self._next_sentence)
        QShortcut(QKeySequence(Qt.Key.Key_N), self, self._add_note)

    def _wire_transport(self) -> None:
        self._transport.play_pause.connect(self._toggle_play)
        self._transport.previous_sentence.connect(self._previous_sentence)
        self._transport.next_sentence.connect(self._next_sentence)
        self._transport.previous_paragraph.connect(self._previous_paragraph)
        self._transport.next_paragraph.connect(self._next_paragraph)
        self._transport.add_note.connect(self._add_note)
        self._transport.export_notes.connect(self._export_notes)
        self._transport.speed_changed.connect(self._on_speed)
        self._transport.voice_changed.connect(self._on_voice_picked)

    def _open_dialog(self) -> None:
        path, _ = QFileDialog.getOpenFileName(
            self,
            "Open manuscript",
            str(Path.home() / "Desktop"),
            "Markdown (*.md);;Text (*.txt);;All files (*.*)",
        )
        if path:
            self._open_path(Path(path), 0)

    def _open_path(self, path: Path, sentence_index: int = 0) -> None:
        self._playing = False
        self._highlight_timer.stop()
        self._engine.stop()
        self._transport.set_playing(False)
        try:
            manuscript = load_manuscript(path)
        except OSError as exc:
            QMessageBox.warning(self, "Ultidraft", f"Could not open file:\n{exc}")
            return
        if not manuscript.sentences:
            QMessageBox.warning(self, "Ultidraft", "That file has no readable text.")
            return
        self._manuscript = manuscript
        self._sidecar = load_sidecar(path, manuscript.hash)
        stale = bool(self._sidecar.notes) and self._sidecar.manuscript_hash != manuscript.hash
        self._sidecar.manuscript_hash = manuscript.hash
        self._index = max(0, min(sentence_index, len(manuscript.sentences) - 1))
        self._reader.set_manuscript(manuscript)
        self._fill_chapters()
        self._notes.set_notes(self._sidecar.notes)
        self._show_index(self._index)
        status = f"{path.name}  Â·  {len(manuscript.chapters)} chapters  Â·  {len(manuscript.sentences)} sentences"
        if stale:
            status += "  Â·  notes were taken on an older draft"
        self.statusBar().showMessage(status)
        self._schedule_save()

    def _fill_chapters(self) -> None:
        self._chapters.clear()
        if self._manuscript is None:
            return
        for chapter in self._manuscript.chapters:
            item = QListWidgetItem(chapter.title)
            item.setData(Qt.ItemDataRole.UserRole, chapter.start_index)
            self._chapters.addItem(item)

    def _on_chapter_clicked(self, item: QListWidgetItem) -> None:
        start = item.data(Qt.ItemDataRole.UserRole)
        if start is not None:
            self._jump_to(int(start))

    def _current_sentence_text(self) -> str:
        if self._manuscript is None:
            return ""
        sentence = self._manuscript.sentence_at(self._index)
        return sentence.text if sentence else ""

    def _show_index(self, index: int) -> None:
        if self._manuscript is None:
            return
        self._index = max(0, min(index, len(self._manuscript.sentences) - 1))
        self._reader.highlight(self._index)
        sentence = self._manuscript.sentence_at(self._index)
        if sentence:
            self._select_chapter(sentence.chapter_id)
        self._schedule_save()

    def _select_chapter(self, chapter_id: str) -> None:
        if self._manuscript is None:
            return
        for row, chapter in enumerate(self._manuscript.chapters):
            if chapter.id == chapter_id:
                self._chapters.blockSignals(True)
                self._chapters.setCurrentRow(row)
                self._chapters.blockSignals(False)
                return

    def _jump_to(self, index: int) -> None:
        was_playing = self._playing
        self._engine.stop()
        self._show_index(index)
        if was_playing:
            self._speak_current()

    def _toggle_play(self) -> None:
        if self._manuscript is None:
            return
        if self._playing:
            self._playing = False
            self._highlight_timer.stop()
            self._engine.pause()
            self._transport.set_playing(False)
            self._flush_state()
            return
        self._playing = True
        self._transport.set_playing(True)
        if self._engine.is_paused():
            self._engine.resume()
            self._highlight_timer.start()
        else:
            self._speak_current()

    def _speak_current(self) -> None:
        if self._manuscript is None or not self._playing:
            return
        span = self._manuscript.span_from(self._index)
        if span is None:
            return
        self._span_end = span.end_index
        self._span_parts = span.sentences
        self._show_index(self._index)
        self._engine.speak(self._spoken_text(span.text))
        ahead = [
            self._spoken_text(text)
            for text in self._manuscript.span_texts_ahead(span.end_index + 1, 3)
        ]
        self._engine.prefetch_many(ahead)
        self._highlight_timer.start()

    def _sync_span_highlight(self) -> None:
        if not self._playing or not self._span_parts:
            return
        ratio = self._engine.playback_ratio()
        if ratio is None:
            return
        total = sum(len(part.text) + 1 for part in self._span_parts)
        if total <= 0:
            return
        target = ratio * total
        acc = 0
        chosen = self._span_parts[0]
        for part in self._span_parts:
            acc += len(part.text) + 1
            chosen = part
            if acc >= target:
                break
        if chosen.index != self._index:
            self._index = chosen.index
            self._reader.highlight(self._index)
            self._select_chapter(chosen.chapter_id)

    def _on_utterance_finished(self) -> None:
        if not self._playing or self._manuscript is None:
            return
        nxt = self._span_end + 1
        if nxt >= len(self._manuscript.sentences):
            self._playing = False
            self._highlight_timer.stop()
            self._transport.set_playing(False)
            self._flush_state()
            return
        self._index = nxt
        self._speak_current()

    def _previous_sentence(self) -> None:
        if self._manuscript is None:
            return
        self._jump_to(self._index - 1)

    def _next_sentence(self) -> None:
        if self._manuscript is None:
            return
        self._jump_to(self._index + 1)

    def _previous_paragraph(self) -> None:
        if self._manuscript is None:
            return
        self._jump_to(self._manuscript.previous_paragraph_index(self._index))

    def _next_paragraph(self) -> None:
        if self._manuscript is None:
            return
        self._jump_to(self._manuscript.next_paragraph_index(self._index))

    def _spoken_text(self, text: str) -> str:
        rules = self._sidecar.lexicon if self._sidecar is not None else []
        return apply_lexicon(text, rules)

    def _edit_lexicon(self) -> None:
        if self._sidecar is None:
            QMessageBox.information(
                self,
                "Ultidraft",
                "Open a manuscript first. Pronunciation rules are saved with that book.",
            )
            return
        dialog = LexiconDialog(self._sidecar.lexicon, self)
        if dialog.exec() != QDialog.DialogCode.Accepted:
            return
        self._sidecar.lexicon = dialog.rules()
        self._flush_state()
        count = len(self._sidecar.lexicon)
        self.statusBar().showMessage(
            f"Saved {count} pronunciation rule{'s' if count != 1 else ''} for this book"
        )

    def _on_speed(self, speed: float) -> None:
        self._speed = speed
        self._engine.set_speed(speed)
        self._schedule_save()

    def _populate_voices(self) -> None:
        choices = self._engine.available_voices()
        self._transport.set_voices(choices, self._voice_id)
        self._voice_menu.clear()
        lexicon_action = QAction("Pronunciation rulesâ€¦", self)
        lexicon_action.triggered.connect(self._edit_lexicon)
        self._voice_menu.addAction(lexicon_action)
        self._voice_menu.addSeparator()
        group = QActionGroup(self)
        group.setExclusive(True)
        self._voice_actions = group
        last_backend = ""
        for choice in choices:
            if choice.backend != last_backend:
                if last_backend:
                    self._voice_menu.addSeparator()
                heading = (
                    "Neural (internet)"
                    if choice.backend == "edge"
                    else "This PC"
                )
                label = QAction(heading, self)
                label.setEnabled(False)
                self._voice_menu.addAction(label)
                last_backend = choice.backend
            action = QAction(choice.label, self)
            action.setCheckable(True)
            action.setData(choice.id)
            action.setChecked(choice.id == self._voice_id)
            action.triggered.connect(lambda checked, voice_id=choice.id: self._on_voice_picked(voice_id))
            group.addAction(action)
            self._voice_menu.addAction(action)
        self._engine.set_voice(self._voice_id)

    def _on_voice_picked(self, voice_id: str) -> None:
        self._apply_voice(voice_id, persist=True)

    def _apply_voice(self, voice_id: str, *, persist: bool) -> None:
        was_playing = self._playing
        self._playing = False
        self._highlight_timer.stop()
        self._engine.stop()
        self._transport.set_playing(False)
        self._voice_id = voice_id
        self._engine.set_voice(voice_id)
        self._transport.set_voice_id(voice_id)
        if self._voice_actions:
            for action in self._voice_actions.actions():
                action.setChecked(action.data() == voice_id)
        if persist:
            self._schedule_save()
        if was_playing:
            self._playing = True
            self._transport.set_playing(True)
            self._speak_current()

    def _on_voice_failed(self, message: str) -> None:
        self._playing = False
        self._transport.set_playing(False)
        fallback = next(
            (choice.id for choice in self._engine.available_voices() if choice.backend == "local"),
            "",
        )
        if fallback:
            self._apply_voice(fallback, persist=True)
        QMessageBox.warning(
            self,
            "Ultidraft",
            "Could not reach a neural voice. "
            f"Switched to a voice installed on this PC.\n\n{message}",
        )

    def _add_note(self) -> None:
        if self._manuscript is None or self._sidecar is None:
            return
        self._playing = False
        self._highlight_timer.stop()
        self._engine.pause()
        self._transport.set_playing(False)
        sentence = self._manuscript.sentence_at(self._index)
        if sentence is None:
            return
        dialog = NoteDialog(self._manuscript.nearby(self._index), self._index, self)
        if dialog.exec() != QDialog.DialogCode.Accepted:
            return
        body = dialog.note_text()
        if not body:
            return
        target = self._manuscript.sentence_at(dialog.selected_index()) or sentence
        before = self._manuscript.sentence_at(target.index - 1)
        after = self._manuscript.sentence_at(target.index + 1)
        note = Note(
            id=self._sidecar.next_note_id(),
            created=utc_now_iso(),
            chapter_title=target.chapter_title,
            anchor_quote=target.text,
            context_before=before.text if before else "",
            context_after=after.text if after else "",
            sentence_index=target.index,
            body=body,
        )
        self._sidecar.add_note(note)
        self._notes.set_notes(self._sidecar.notes)
        self._flush_state()
        write_notes_markdown(
            default_export_path(self._manuscript.path),
            self._sidecar,
            self._manuscript.path.name,
        )
        self.statusBar().showMessage(
            f"Saved {note.id} and updated listening-notes.md"
        )

    def _export_notes(self) -> None:
        if self._manuscript is None or self._sidecar is None:
            return
        if not self._sidecar.notes:
            QMessageBox.information(self, "Ultidraft", "There are no notes to export yet.")
            return
        target = default_export_path(self._manuscript.path)
        write_notes_markdown(target, self._sidecar, self._manuscript.path.name)
        QMessageBox.information(
            self,
            "Ultidraft",
            f"Exported {len(self._sidecar.notes)} notes to:\n{target}",
        )

    def _about(self) -> None:
        QMessageBox.information(
            self,
            "About Ultidraft",
            "Ultidraft listens to a markdown draft,\n"
            "lets you mark the sentence you just heard,\n"
            "and exports quote-anchored notes for Cursor.\n\n"
            "Neural voices use Microsoft Edge TTS (one sentence\n"
            "at a time, no account). This-PC voices stay offline.\n"
            "Spoken notes use Windows speech recognition on this PC.\n"
            "Pronunciation rules are saved with the book.\n"
            "Notes stay beside the book.",
        )

    def _schedule_save(self) -> None:
        self._save_timer.start()

    def _flush_state(self) -> None:
        if self._manuscript is None or self._sidecar is None:
            existing = load_session()
            existing.speed = self._speed
            existing.voice_id = self._voice_id
            save_session(existing)
            return
        sentence = self._manuscript.sentence_at(self._index)
        self._sidecar.manuscript_path = self._manuscript.path.name
        self._sidecar.manuscript_hash = self._manuscript.hash
        self._sidecar.position = {
            "chapter_id": sentence.chapter_id if sentence else "",
            "sentence_index": self._index,
        }
        save_sidecar(self._manuscript.path, self._sidecar)
        save_session(
            Session(
                last_file=str(self._manuscript.path),
                chapter_id=self._sidecar.position["chapter_id"],
                sentence_index=self._index,
                speed=self._speed,
                voice_id=self._voice_id,
            )
        )

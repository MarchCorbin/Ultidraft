"""Ultifile / Ultiplay dark chrome."""

STYLESHEET = """
QMainWindow, QWidget {
    background: #1e1e1e;
    color: #e0e0e0;
    font-family: "Segoe UI";
    font-size: 13px;
}
QMenuBar {
    background: #1e1e1e;
    color: #e0e0e0;
    border-bottom: 1px solid #333333;
}
QMenuBar::item:selected { background: #2b2b2b; }
QMenu {
    background: #2b2b2b;
    color: #e0e0e0;
    border: 1px solid #333333;
}
QMenu::item:selected { background: #3d4f66; }
QSplitter::handle { background: #333333; width: 1px; height: 1px; }
QListWidget, QTextEdit, QPlainTextEdit, QTableWidget, QLineEdit {
    background: #2b2b2b;
    color: #e0e0e0;
    border: 1px solid #333333;
    selection-background-color: #3d4f66;
    selection-color: #ffffff;
}
QListWidget::item { padding: 8px 10px; }
QListWidget::item:selected { background: #3d4f66; color: #ffffff; }
QListWidget::item:hover { background: #333333; }
QPushButton {
    background: #3a3a3a;
    color: #e0e0e0;
    border: 1px solid #444444;
    border-radius: 4px;
    padding: 6px 12px;
    min-height: 24px;
}
QPushButton:hover { background: #4a4a4a; }
QPushButton:pressed { background: #333333; }
QPushButton:disabled { color: #6a6a6a; }
QSlider::groove:horizontal {
    height: 4px;
    background: #333333;
    border-radius: 2px;
}
QSlider::handle:horizontal {
    background: #0078d4;
    width: 14px;
    margin: -6px 0;
    border-radius: 7px;
}
QLabel#paneTitle {
    color: #9a9a9a;
    font-size: 11px;
    letter-spacing: 0.08em;
    padding: 8px 10px 4px;
}
QLabel#statusMuted { color: #9a9a9a; }
QStatusBar {
    background: #1e1e1e;
    color: #9a9a9a;
    border-top: 1px solid #333333;
}
QDialog { background: #1e1e1e; }
QComboBox {
    background: #2b2b2b;
    color: #e0e0e0;
    border: 1px solid #444444;
    border-radius: 4px;
    padding: 4px 8px;
    min-height: 24px;
    min-width: 220px;
}
QComboBox:hover { background: #333333; }
QComboBox::drop-down { border: none; }
QHeaderView::section {
    background: #2b2b2b;
    color: #9a9a9a;
    border: none;
    border-bottom: 1px solid #333333;
    padding: 6px 8px;
}
QTableWidget::item:selected { background: #3d4f66; color: #ffffff; }
QLineEdit {
    padding: 6px 8px;
    border-radius: 4px;
}
QPlainTextEdit#manuscriptEditor {
    background: #2b2b2b;
    color: #e8e4d9;
    border: none;
    font-family: "Cascadia Mono", Consolas, monospace;
    font-size: 14px;
    selection-background-color: #3d4f66;
}
QLabel#editBanner {
    background: #3d4f66;
    color: #ffffff;
    padding: 6px 12px;
}
QComboBox QAbstractItemView {
    background: #2b2b2b;
    color: #e0e0e0;
    selection-background-color: #3d4f66;
    border: 1px solid #333333;
}
"""

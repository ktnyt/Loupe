package jp.nano.rawgallery;
import java.util.ArrayDeque;
/** Gesture-sized, bounded recipe undo. Cancelled gestures never enter the history. */
final class RawEditHistory {
  private final ArrayDeque<RawEditState> undo=new ArrayDeque<>(),redo=new ArrayDeque<>();
  void push(RawEditState before,RawEditState after){if(before.equals(after))return;if(undo.size()==64)undo.removeFirst();undo.addLast(before);redo.clear();}
  boolean canUndo(){return !undo.isEmpty();}boolean canRedo(){return !redo.isEmpty();}
  RawEditState undo(RawEditState current){if(!canUndo())return current;redo.addLast(current);return undo.removeLast();}
  RawEditState redo(RawEditState current){if(!canRedo())return current;undo.addLast(current);return redo.removeLast();}
  void clear(){undo.clear();redo.clear();}
}

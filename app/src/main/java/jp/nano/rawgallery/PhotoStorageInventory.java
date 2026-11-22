package jp.nano.rawgallery;

import android.net.Uri;
import java.util.List;

/** An on-device snapshot of files and unresolved references in the photo import histories. */
record PhotoStorageInventory(List<Photo> photos, List<History> unresolved) {
  PhotoStorageInventory {
    photos = List.copyOf(photos);
    unresolved = List.copyOf(unresolved);
  }

  enum Status { ELIGIBLE, ALREADY_THERE, EXCLUDED }

  record Photo(Uri uri, String name, PhotoStorageLocation location, Status status, List<Reason> reasons,
      String recordedName) {
    Photo {
      reasons = List.copyOf(reasons);
    }
  }

  /** Count is the number of records represented by this row, regardless of physical file count. */
  record History(String name, PhotoStorageLocation location, Reason reason, int count) {}

  enum Reason {
    OWNER_UNKNOWN("所有者を確認できない", "端末のファイルアプリで写真を確認してください。"),
    OTHER_OWNER("他のアプリが所有", "保存元のアプリか端末のファイルアプリで写真を確認してください。"),
    PENDING("保存処理が完了していない", "保存が終わってから再読み込みしてください。"),
    TRASHED("ゴミ箱にある", "必要な写真は、ゴミ箱から復元してから再読み込みしてください。"),
    SIZE_UNKNOWN("写真のサイズを確認できない", "元の写真を開けるか確認してください。"),
    UNSUPPORTED_FOLDER("取り込み先のフォルダ外にある", "表示されたフォルダを端末のファイルアプリで確認してください。"),
    UNSUPPORTED_FORMAT("移動に対応していない形式", "必要な場合は端末のファイルアプリで写真を確認してください。"),
    VOLUME_UNAVAILABLE("移動元を読み書きできない", "カードの接続と書き込み状態を確認してください。"),
    DESTINATION_UNAVAILABLE("選択中の保存先に書き込めない", "カードを接続するか書き込める保存先を選んでください。"),
    AMBIGUOUS_REFERENCE("履歴の参照先を一つに特定できない", "記録上の名前を手がかりに、元の写真を確認してください。"),
    MISSING_REFERENCE("履歴の参照先を確認できない", "カードの接続や写真へのアクセスを確認してください。"),
    NO_FILE_REFERENCE("履歴にファイルの参照先がない", "記録上の名前を手がかりに、元の写真を確認してください。"),
    CONFLICTING_HISTORY("複数の取り込み記録が一致しない", "元の写真と取り込み記録を確認してください。"),
    HISTORY_NAME("履歴と写真の名前が一致しない", "表示中のファイルと履歴の写真が同じものか確認してください。"),
    HISTORY_SIZE("履歴と写真のサイズが一致しない", "表示中のファイルと履歴の写真が同じものか確認してください。"),
    HISTORY_CAPTURE_MISSING("写真の撮影日時を確認できない", "表示中のファイルと履歴の写真が同じものか確認してください。"),
    HISTORY_CAPTURE_DIFFERS("履歴と写真の撮影日時が一致しない", "表示中のファイルと履歴の写真が同じものか確認してください。");

    private final String label;
    private final String action;

    Reason(String label, String action) {
      this.label = label;
      this.action = action;
    }

    String label() { return label; }
    String action() { return action; }
  }
}

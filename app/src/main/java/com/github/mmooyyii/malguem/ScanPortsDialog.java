package com.github.mmooyyii.malguem;

import android.app.AlertDialog;
import android.content.Context;
import android.view.LayoutInflater;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.RadioGroup;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

// 扫描设置: 列出要扫的端口, OK 键开关, 长按 OK 删自定义项, 底下按钮加新端口.
// 端口不标准的自建服务 (改过端口的 Komga、非 5244 的 alist) 靠这里补
public class ScanPortsDialog {

    private final Context ctx;
    private final List<ScanPort> ports;
    private final List<String> labels = new ArrayList<>();
    private ArrayAdapter<String> adapter;

    private ScanPortsDialog(Context ctx) {
        this.ctx = ctx;
        this.ports = ScanPort.load(ctx);
    }

    public static void show(Context ctx) {
        new ScanPortsDialog(ctx).build();
    }

    private void build() {
        var view = LayoutInflater.from(ctx).inflate(R.layout.dialog_scanports, null);
        ListView list = view.findViewById(R.id.portList);
        adapter = new ArrayAdapter<>(ctx, R.layout.item_port, R.id.portName, labels);
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, v, position, id) -> {
            var p = ports.get(position);
            p.on = !p.on;
            ScanPort.save(ctx, ports);
            refresh();
        });
        list.setOnItemLongClickListener((parent, v, position, id) -> {
            var p = ports.get(position);
            if (p.builtin) {
                Toast.makeText(ctx, R.string.scan_port_builtin, Toast.LENGTH_SHORT).show();
                return true;
            }
            ports.remove(position);
            ScanPort.save(ctx, ports);
            refresh();
            return true;
        });
        refresh();
        new AlertDialog.Builder(ctx)
                .setTitle(R.string.scan_settings)
                .setView(view)
                .setPositiveButton(R.string.scan_port_add, (d, which) -> showAdd())
                .setNegativeButton(R.string.close, null)
                .show();
        list.requestFocus();
    }

    private void refresh() {
        labels.clear();
        for (var p : ports) {
            labels.add((p.on ? "✓  " : "○  ") + p.name + "   " + p.port
                    + (p.path.isEmpty() ? "" : p.path));
        }
        adapter.notifyDataSetChanged();
    }

    private void showAdd() {
        var view = LayoutInflater.from(ctx).inflate(R.layout.dialog_scanport_add, null);
        EditText etName = view.findViewById(R.id.et_name);
        EditText etPort = view.findViewById(R.id.et_port);
        EditText etPath = view.findViewById(R.id.et_path);
        RadioGroup rgKind = view.findViewById(R.id.rg_kind);
        new AlertDialog.Builder(ctx)
                .setTitle(R.string.scan_port_add)
                .setView(view)
                .setPositiveButton(R.string.ok_add, (d, which) -> {
                    int port;
                    try {
                        port = Integer.parseInt(etPort.getText().toString().trim());
                    } catch (Exception e) {
                        port = 0;
                    }
                    if (port < 1 || port > 65535) {
                        Toast.makeText(ctx, R.string.scan_port_bad, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String kind = ScanPort.KIND_WEBDAV;
                    if (rgKind.getCheckedRadioButtonId() == R.id.rb_smb) {
                        kind = ScanPort.KIND_SMB;
                    } else if (rgKind.getCheckedRadioButtonId() == R.id.rb_opds) {
                        kind = ScanPort.KIND_OPDS;
                    }
                    var name = etName.getText().toString().trim();
                    if (name.isEmpty()) {
                        name = kind.toUpperCase() + " " + port;
                    }
                    ports.add(new ScanPort(name, port, kind, etPath.getText().toString().trim(), true, false));
                    ScanPort.save(ctx, ports);
                    show(ctx); // 加完回到清单, 让主人看见结果
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }
}

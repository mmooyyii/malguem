package com.github.mmooyyii.malguem;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import java.util.List;
import java.util.Objects;

public class FileListAdapter extends ArrayAdapter<File> {

    public FileListAdapter(Context context) {
        super(context, R.layout.list_item_file);
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        if (convertView == null) {
            convertView = LayoutInflater.from(getContext()).inflate(R.layout.list_item_file, parent, false);
        }

        TextView fileNameTextView = convertView.findViewById(R.id.leftTextView);
        var file = Objects.requireNonNull(getItem(position));
        fileNameTextView.setText(file.name);

        TextView fileTypeTextView = convertView.findViewById(R.id.rightTextView);
        if (file.type == FileType.Epub) {
            if (file.view_type == ViewType.Novel) {
                fileTypeTextView.setText("小说");
            } else {
                fileTypeTextView.setText("漫画");
            }
        }
        return convertView;
    }
}
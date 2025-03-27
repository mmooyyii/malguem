package com.github.mmooyyii.malguem;


import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import androidx.annotation.NonNull;

import java.util.Objects;

public class FileListAdapter extends ArrayAdapter<ListItem> {

    public FileListAdapter(Context context) {
        super(context, R.layout.list_item_file);
    }

    @NonNull
    @Override
    public View getView(int position, View convertView, @NonNull ViewGroup parent) {
        if (convertView == null) {
            convertView = LayoutInflater.from(getContext()).inflate(R.layout.list_item_file, parent, false);
        }

        TextView fileNameTextView = convertView.findViewById(R.id.leftTextView);
        var file = Objects.requireNonNull(getItem(position));
        fileNameTextView.setText(file.name);

        TextView fileTypeTextView = convertView.findViewById(R.id.rightTextView);
        String tail = "";
        if (file.type == ListItem.FileType.Epub) {
            if (file.view_type == ListItem.ViewType.Novel) {
                tail = "小说 ";
            } else {
                tail = "漫画 ";
            }
            if (file.total_page == 0) {
                tail += "未读";
            } else {
                tail += (file.read_to_page + 1) + "/" + file.total_page;
            }
        }
        fileTypeTextView.setText(tail);
        return convertView;
    }
}
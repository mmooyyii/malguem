package com.github.mmooyyii.malguem;


import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class FileListAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_ITEM = 0;
    private static final int TYPE_HEADER = 1;

    public interface OnItemAction {
        void onClick(ListItem item);

        // 长按 OK = 菜单键的兜底 (不少 Google TV 遥控器没有菜单键); 返回是否已处理
        default boolean onLongClick(ListItem item) {
            return false;
        }
    }

    // 生成书封(无封面图时)的柔和色板
    private static final int[] COVER_COLORS = {
            0xFFC57B57, 0xFF7C8A6B, 0xFF6B7B8A, 0xFFA6789B, 0xFFC0983F, 0xFF8A6B5B
    };

    private final Context context;
    private final CoverLoader coverLoader;
    private final List<ListItem> items = new ArrayList<>();
    private ResourceInterface client;
    private String namespace = "";
    private OnItemAction action;

    public FileListAdapter(Context context) {
        this.context = context;
        this.coverLoader = CoverLoader.get(context);
    }

    public void setOnItemAction(OnItemAction action) {
        this.action = action;
    }

    public void setClient(ResourceInterface client) {
        this.client = client;
        this.namespace = client == null ? "" : client.to_json();
    }

    public void setItems(List<ListItem> list) {
        items.clear();
        if (list != null) {
            items.addAll(list);
        }
        notifyDataSetChanged();
    }

    public ListItem getItem(int position) {
        return items.get(position);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @Override
    public int getItemViewType(int position) {
        return items.get(position).type == ListItem.FileType.Header ? TYPE_HEADER : TYPE_ITEM;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == TYPE_HEADER) {
            return new HeaderVH(LayoutInflater.from(context).inflate(R.layout.grid_item_header, parent, false));
        }
        View v = LayoutInflater.from(context).inflate(R.layout.grid_item_file, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        var item = items.get(position);
        if (holder instanceof HeaderVH) {
            ((HeaderVH) holder).title.setText(item.name);
            return;
        }
        VH h = (VH) holder;

        // 复用前先重置
        h.coverImage.setVisibility(View.GONE);
        h.coverImage.setImageDrawable(null);
        h.coverImage.setTag(R.id.cover_key_tag, null);
        h.coverTitle.setVisibility(View.GONE);
        h.coverIcon.setVisibility(View.GONE);
        h.coverSpine.setVisibility(View.GONE);
        h.coverProgress.setVisibility(View.GONE);
        h.sub.setVisibility(View.GONE);

        var display = stripExt(item.name == null ? "" : item.name);

        switch (item.type) {
            case Epub:
            case RecentEpub:
                bindEpub(h, item, display);
                break;
            case Dir:
                h.cover.setBackgroundResource(R.drawable.cover_tile);
                h.coverIcon.setImageResource(R.drawable.ic_folder);
                h.coverIcon.setVisibility(View.VISIBLE);
                h.caption.setText(display);
                break;
            case Resource:
                h.cover.setBackgroundResource(R.drawable.cover_tile);
                h.coverIcon.setImageResource(resourceIcon(item.resource_type));
                h.coverIcon.setVisibility(View.VISIBLE);
                h.caption.setText(display);
                h.sub.setText(typeName(item.resource_type));
                h.sub.setVisibility(View.VISIBLE);
                break;
            case AddWebDav:
                h.cover.setBackgroundResource(R.drawable.cover_add);
                h.coverIcon.setImageResource(R.drawable.ic_add);
                h.coverIcon.setVisibility(View.VISIBLE);
                h.caption.setText(display);
                break;
            case CheckUpdate:
                h.cover.setBackgroundResource(R.drawable.cover_tile);
                h.coverIcon.setImageResource(R.drawable.ic_update);
                h.coverIcon.setVisibility(View.VISIBLE);
                h.caption.setText(display);
                break;
            case RebuildIndex:
                h.cover.setBackgroundResource(R.drawable.cover_tile);
                h.coverIcon.setImageResource(R.drawable.ic_reindex);
                h.coverIcon.setVisibility(View.VISIBLE);
                h.caption.setText(display);
                break;
        }

        h.itemView.setOnClickListener(v -> {
            if (action != null) {
                action.onClick(item);
            }
        });
        h.itemView.setOnLongClickListener(v -> action != null && action.onLongClick(item));
    }

    private void bindEpub(VH h, ListItem item, String display) {
        // 兜底书封: 由标题 hash 决定的渐变色 + 标题文字 + 书脊
        int base = COVER_COLORS[Math.floorMod(display.hashCode(), COVER_COLORS.length)];
        var gd = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{lighten(base, 0.18f), base});
        gd.setCornerRadius(context.getResources().getDimension(R.dimen.cover_radius));
        h.cover.setBackground(gd);
        h.coverTitle.setText(display);
        h.coverTitle.setVisibility(View.VISIBLE);
        h.coverSpine.setVisibility(View.VISIBLE);

        if (item.total_page > 0) {
            h.coverProgress.setMax(item.total_page);
            h.coverProgress.setProgress(Math.min(item.read_to_page + 1, item.total_page));
            h.coverProgress.setVisibility(View.VISIBLE);
        }

        h.caption.setText(display);
        var type = context.getString(item.view_type == ListItem.ViewType.Novel ? R.string.novel_label : R.string.comic_label);
        var status = item.total_page == 0 ? context.getString(R.string.unread)
                : (item.read_to_page + 1) + " / " + item.total_page;
        h.sub.setText(type + " · " + status);
        h.sub.setVisibility(View.VISIBLE);

        // 真实封面 (异步加载, 成功后盖在兜底书封之上); RecentEpub 自带 namespace, 其余用当前浏览的数据源
        var uri = item.uri != null ? item.uri : item.name;
        var ns = item.ns != null ? item.ns : namespace;
        var c = item.ns != null ? ResourceInterface.from_json(item.ns) : client;
        if (c != null && uri != null) {
            coverLoader.load(ns, uri, c, h.coverImage);
        }
    }

    private static int resourceIcon(int type) {
        switch (type) {
            case 2:
                return R.drawable.ic_nas;
            case 3:
                return R.drawable.ic_storage;
            case 4:
                return R.drawable.ic_books;
            default:
                return R.drawable.ic_cloud;
        }
    }

    // 数据源格子下的类型小字
    private String typeName(int type) {
        switch (type) {
            case 2:
                return "SMB";
            case 3:
                return context.getString(R.string.source_local);
            case 4:
                return "OPDS";
            default:
                return "WebDAV";
        }
    }

    private static String stripExt(String name) {
        if (name.toLowerCase().endsWith(".epub")) {
            return name.substring(0, name.length() - 5);
        }
        return name;
    }

    private static int lighten(int color, float f) {
        int a = (color >> 24) & 0xFF;
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        r = (int) (r + (255 - r) * f);
        g = (int) (g + (255 - g) * f);
        b = (int) (b + (255 - b) * f);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    static class HeaderVH extends RecyclerView.ViewHolder {
        final TextView title;

        HeaderVH(View v) {
            super(v);
            title = (TextView) v;
        }
    }

    static class VH extends RecyclerView.ViewHolder {
        final FrameLayout cover;
        final ImageView coverImage;
        final ImageView coverIcon;
        final TextView coverTitle;
        final View coverSpine;
        final ProgressBar coverProgress;
        final TextView caption;
        final TextView sub;

        VH(View v) {
            super(v);
            cover = v.findViewById(R.id.cover);
            coverImage = v.findViewById(R.id.coverImage);
            coverIcon = v.findViewById(R.id.coverIcon);
            coverTitle = v.findViewById(R.id.coverTitle);
            coverSpine = v.findViewById(R.id.coverSpine);
            coverProgress = v.findViewById(R.id.coverProgress);
            caption = v.findViewById(R.id.caption);
            sub = v.findViewById(R.id.sub);
            coverImage.setClipToOutline(true); // 真实封面按圆角背景裁切
        }
    }
}

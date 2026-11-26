package com.livetv.ui.dialog;

import android.app.Activity;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatDialogFragment;
import androidx.fragment.app.FragmentActivity;

import com.livetv.R;
import com.livetv.model.LiveSource;

public class SourceEditDialog extends AppCompatDialogFragment {

    public interface Listener {
        void onSourceSaved(int index, LiveSource source);

        void onSourceDeleted(int index);
    }

    private static final String ARG_INDEX = "index";
    private static final String ARG_NAME = "name";
    private static final String ARG_URL = "url";
    private static final String ARG_EPG = "epg";
    private static final String ARG_UA = "ua";
    private static final int REQUEST_FILE = 1001;

    private EditText name;
    private EditText url;
    private EditText epg;
    private EditText ua;
    private int index;
    private boolean editing;

    public static void show(FragmentActivity activity, int index, LiveSource source) {
        SourceEditDialog dialog = new SourceEditDialog();
        Bundle args = new Bundle();
        args.putInt(ARG_INDEX, index);
        if (source != null) {
            args.putString(ARG_NAME, source.name);
            args.putString(ARG_URL, source.url);
            args.putString(ARG_EPG, source.epg);
            args.putString(ARG_UA, source.ua);
        }
        dialog.setArguments(args);
        dialog.show(activity.getSupportFragmentManager(), "source_edit");
    }

    @NonNull
    @Override
    public AlertDialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        Bundle args = getArguments() == null ? new Bundle() : getArguments();
        index = args.getInt(ARG_INDEX, -1);
        editing = index >= 0;
        final View view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_source, null);
        ((android.widget.TextView) view.findViewById(R.id.dialog_title))
                .setText(editing ? R.string.edit_source : R.string.new_source);
        name = view.findViewById(R.id.edit_name);
        url = view.findViewById(R.id.edit_url);
        epg = view.findViewById(R.id.edit_epg);
        ua = view.findViewById(R.id.edit_ua);
        name.setText(args.getString(ARG_NAME, ""));
        url.setText(args.getString(ARG_URL, ""));
        epg.setText(args.getString(ARG_EPG, ""));
        ua.setText(args.getString(ARG_UA, ""));
        view.findViewById(R.id.btn_file).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickFile();
            }
        });
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext(), R.style.DialogTheme)
                .setView(view)
                .setPositiveButton(R.string.save, null)
                .setNegativeButton(R.string.cancel, null);
        if (editing) builder.setNeutralButton(R.string.delete, null);
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface d) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        save();
                    }
                });
                if (editing) {
                    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            confirmDelete();
                        }
                    });
                }
            }
        });
        return dialog;
    }

    @Override
    public void onStart() {
        super.onStart();
        Window window = getDialog() == null ? null : getDialog().getWindow();
        if (window == null) return;
        int screen = getResources().getDisplayMetrics().widthPixels;
        int width = Math.min(screen - dp(32), dp(720));
        window.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    private void pickFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        try {
            startActivityForResult(intent, REQUEST_FILE);
        } catch (Exception e) {
            Toast.makeText(requireContext(), "无法打开文件选择器", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_FILE || resultCode != Activity.RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        try {
            requireContext().getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {
        }
        url.setText(uri.toString());
    }

    private void save() {
        String textName = name.getText().toString().trim();
        String textUrl = url.getText().toString().trim();
        if (textUrl.isEmpty()) {
            Toast.makeText(requireContext(), "请填写直播源地址", Toast.LENGTH_SHORT).show();
            return;
        }
        if (textName.isEmpty()) textName = "直播源 " + (Math.max(0, index) + 1);
        LiveSource source = new LiveSource(textName, textUrl);
        source.epg = epg.getText().toString().trim();
        source.ua = ua.getText().toString().trim();
        if (!(getActivity() instanceof Listener)) {
            dismiss();
            return;
        }
        ((Listener) getActivity()).onSourceSaved(index, source);
        dismiss();
    }

    private void confirmDelete() {
        new AlertDialog.Builder(requireContext(), R.style.DialogTheme)
                .setMessage(R.string.delete_source_confirm)
                .setPositiveButton(R.string.delete, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (getActivity() instanceof Listener) ((Listener) getActivity()).onSourceDeleted(index);
                        dismiss();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }
}

package com.wangzan101.mika.cap.phone.a11y;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.TextView;

/** 测试包的启动页:账号认证器包的普通页面(不是登录页),用来验证它仍会被挪回 Mika 的屏。 */
public class TestLauncherActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView text = new TextView(this);
        text.setText("Test launcher");
        text.setTextSize(22f);
        text.setGravity(Gravity.CENTER);
        setContentView(text);
    }
}

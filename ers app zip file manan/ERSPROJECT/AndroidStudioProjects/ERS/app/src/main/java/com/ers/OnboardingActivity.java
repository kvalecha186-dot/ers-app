package com.ers;

import android.animation.ArgbEvaluator;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.button.MaterialButton;

public class OnboardingActivity extends AppCompatActivity {

    private ViewPager2        viewPager;
    private MaterialButton    nextButton, skipButton;
    private LinearLayout      indicatorContainer;
    private OnboardingAdapter adapter;
    private int[]             colors;
    private final ArgbEvaluator argbEvaluator = new ArgbEvaluator();

    private final OnboardingItem[] onboardingItems = {
            new OnboardingItem("Welcome to ERS",
                    "Your personal emergency recording system. Stay safe with intelligent audio evidence collection.", "🎤"),
            new OnboardingItem("Quick Trigger",
                    "Press volume buttons 5 times to instantly start recording. Works even when your screen is off.", "▶️"),
            new OnboardingItem("SOS Countdown",
                    "A 5-second countdown gives you time to cancel if triggered by accident. Press 5× again to cancel.", "🚨"),
            new OnboardingItem("Auto Location & Alerts",
                    "Your location is automatically captured and sent to emergency contacts via SMS, email, and WhatsApp.", "📍"),
            new OnboardingItem("You're All Set!",
                    "Grant the required permissions and start protecting yourself. Your safety is our priority.", "✅")
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_onboarding);

        viewPager          = findViewById(R.id.viewPager);
        nextButton         = findViewById(R.id.nextButton);
        skipButton         = findViewById(R.id.skipButton);
        indicatorContainer = findViewById(R.id.indicatorContainer);

        colors = new int[]{
                ContextCompat.getColor(this, R.color.primary),
                ContextCompat.getColor(this, R.color.accent),
                ContextCompat.getColor(this, R.color.info),
                ContextCompat.getColor(this, R.color.success),
                ContextCompat.getColor(this, R.color.primary)
        };

        adapter = new OnboardingAdapter(onboardingItems);
        viewPager.setAdapter(adapter);
        viewPager.setOffscreenPageLimit(onboardingItems.length);

        setupIndicators();
        setCurrentIndicator(0);

        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override public void onPageSelected(int position) {
                setCurrentIndicator(position);
                nextButton.setText(position == onboardingItems.length - 1
                        ? "Get Started" : "Next");
            }
            @Override public void onPageScrolled(int pos, float offset, int pixels) {
                if (pos < colors.length - 1)
                    viewPager.setBackgroundColor((int) argbEvaluator.evaluate(
                            offset, colors[pos], colors[pos + 1]));
            }
        });

        nextButton.setOnClickListener(v -> {
            v.startAnimation(AnimationUtils.loadAnimation(this, R.anim.button_click));
            if (viewPager.getCurrentItem() < onboardingItems.length - 1)
                viewPager.setCurrentItem(viewPager.getCurrentItem() + 1, true);
            else finishOnboarding();
        });

        skipButton.setOnClickListener(v -> {
            v.startAnimation(AnimationUtils.loadAnimation(this, R.anim.button_click));
            finishOnboarding();
        });
    }

    // [BUG 1 FIX] Cancel all animation handlers when Activity is destroyed
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (adapter != null) adapter.cancelAllAnimations();
    }

    private void setupIndicators() {
        for (int i = 0; i < onboardingItems.length; i++) {
            View dot = new View(this);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dpToPx(8), dpToPx(8));
            p.setMargins(dpToPx(4), 0, dpToPx(4), 0);
            dot.setLayoutParams(p);
            dot.setBackgroundResource(R.drawable.indicator_inactive);
            indicatorContainer.addView(dot);
        }
    }

    private void setCurrentIndicator(int position) {
        for (int i = 0; i < indicatorContainer.getChildCount(); i++) {
            View dot = indicatorContainer.getChildAt(i);
            dot.setBackgroundResource(i == position
                    ? R.drawable.indicator_active
                    : R.drawable.indicator_inactive);
            if (i == position)
                dot.startAnimation(AnimationUtils.loadAnimation(this, R.anim.button_click));
        }
    }

    private void finishOnboarding() {
        getSharedPreferences("ERSSettings", MODE_PRIVATE)
                .edit().putBoolean("onboardingCompleted", true).apply();
        startActivity(new Intent(this, AccessibilitySetupActivity.class));
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
        finish();
    }

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }

    // ── Data ──────────────────────────────────────────────────────────────────

    private static class OnboardingItem {
        final String title, description, emoji;
        OnboardingItem(String t, String d, String e) { title=t; description=d; emoji=e; }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Adapter
    //  [BUG 1 FIX] animationHandler stored per-holder and cancelled in
    //  cancelAllAnimations() which is called from onDestroy().
    //  This prevents the memory leak / OOM that occurred previously.
    // ═════════════════════════════════════════════════════════════════════════

    private static class OnboardingAdapter
            extends RecyclerView.Adapter<OnboardingAdapter.OnboardingViewHolder> {

        private final OnboardingItem[] items;
        // One handler per holder — tracked so we can cancel all of them on destroy
        private final Handler[] holderHandlers;
        private boolean destroyed = false;

        OnboardingAdapter(OnboardingItem[] items) {
            this.items         = items;
            this.holderHandlers = new Handler[items.length];
        }

        /** Called from Activity.onDestroy() — stops all pending animation callbacks */
        void cancelAllAnimations() {
            destroyed = true;
            for (Handler h : holderHandlers) {
                if (h != null) h.removeCallbacksAndMessages(null);
            }
        }

        @NonNull @Override
        public OnboardingViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new OnboardingViewHolder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.onboarding_screen, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull OnboardingViewHolder holder, int position) {
            if (position >= items.length) return;
            OnboardingItem item = items[position];
            holder.title.setText(item.title);
            holder.description.setText(item.description);
            holder.emojiText.setText(item.emoji);

            // Create a dedicated handler for this position
            if (holderHandlers[position] == null)
                holderHandlers[position] = new Handler(Looper.getMainLooper());

            final Handler h = holderHandlers[position];
            holder.itemView.post(() -> {
                if (destroyed) return;
                // Slide-up entrance
                Animation slideUp = AnimationUtils.loadAnimation(
                        holder.itemView.getContext(), R.anim.slide_up_fade_in);
                holder.title.startAnimation(slideUp);
                holder.description.startAnimation(slideUp);
                // Floating emoji loop
                startEmojiFloat(holder, h);
                // Ripple loop
                startRipple(holder.rippleCircle1, h, 0);
            });
        }

        private void startEmojiFloat(OnboardingViewHolder holder, Handler h) {
            if (destroyed) return;
            Animation anim = AnimationUtils.loadAnimation(
                    holder.itemView.getContext(), R.anim.emoji_float);
            anim.setAnimationListener(new SimpleAnimListener() {
                @Override public void onAnimationEnd(Animation a) {
                    if (!destroyed)
                        holder.emojiText.post(() -> startEmojiFloat(holder, h));
                }
            });
            holder.emojiText.startAnimation(anim);
        }

        private void startRipple(View ripple, Handler h, long delay) {
            if (destroyed) return;
            h.postDelayed(() -> {
                if (destroyed) return;
                Animation anim = AnimationUtils.loadAnimation(
                        ripple.getContext(), R.anim.circle_ripple);
                anim.setAnimationListener(new SimpleAnimListener() {
                    @Override public void onAnimationEnd(Animation a) {
                        if (!destroyed) ripple.post(() -> startRipple(ripple, h, 0));
                    }
                });
                ripple.startAnimation(anim);
            }, delay);
        }

        @Override public int getItemCount() { return items.length; }

        static class OnboardingViewHolder extends RecyclerView.ViewHolder {
            TextView title, description, emojiText;
            View     rippleCircle1;
            OnboardingViewHolder(@NonNull View v) {
                super(v);
                title         = v.findViewById(R.id.onboardingTitle);
                description   = v.findViewById(R.id.onboardingDescription);
                emojiText     = v.findViewById(R.id.onboardingEmoji);
                rippleCircle1 = v.findViewById(R.id.rippleCircle1);
            }
        }
    }

    /** Convenience base so we only override what we need */
    private static abstract class SimpleAnimListener implements Animation.AnimationListener {
        @Override public void onAnimationStart(Animation a)  {}
        @Override public void onAnimationRepeat(Animation a) {}
    }
}

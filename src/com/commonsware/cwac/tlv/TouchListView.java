/*
 * Copyright (c) 2010 CommonsWare, LLC
 * Portions Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.commonsware.cwac.tlv;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.Log;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.GestureDetector.SimpleOnGestureListener;
import android.widget.AdapterView;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

public class TouchListView extends ListView {
    private ImageView mDragView;
    private WindowManager mWindowManager;
    private WindowManager.LayoutParams mWindowParams;
    private int mDragPos;      // which item is being dragged
    private int mFirstDragPos; // where was the dragged item originally
    private int mDragPoint;    // at what offset inside the item did the user grab it
    private int mCoordOffset;  // the difference between screen coordinates and coordinates in this view
    private DragListener mDragListener;
    private DropListener mDropListener;
    private RemoveListener mRemoveListener;
    private int mUpperBound;
    private int mLowerBound;
    private int mHeight;
    private GestureDetector mGestureDetector;
    public static final int FLING = 0;
    public static final int SLIDE_RIGHT = 1;
    public static final int SLIDE_LEFT = 2;
    private int mRemoveMode = -1;
    private Rect mTempRect = new Rect();
    private Bitmap mDragBitmap;
    private final int mTouchSlop;
    private int mItemHeightNormal = -1;
    private int mItemHeightExpanded = -1;
    private int grabberId = -1;
    private int dragndropBackgroundColor = 0x00000000;

    public TouchListView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public TouchListView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

        if (attrs != null) {
            TypedArray a = getContext()
                    .obtainStyledAttributes(attrs,
                            R.styleable.TouchListView,
                            0, 0);

            mItemHeightNormal = a.getDimensionPixelSize(R.styleable.TouchListView_normal_height, 0);
            mItemHeightExpanded = a.getDimensionPixelSize(R.styleable.TouchListView_expanded_height, mItemHeightNormal);
            grabberId = a.getResourceId(R.styleable.TouchListView_grabber, -1);
            dragndropBackgroundColor = a.getColor(R.styleable.TouchListView_dragndrop_background, 0x00000000);
            mRemoveMode = a.getInt(R.styleable.TouchListView_remove_mode, -1);

            a.recycle();
        }
    }

    @Override
    final public void addHeaderView(View v, Object data, boolean isSelectable) {
        throw new RuntimeException("Headers are not supported with TouchListView");
    }

    @Override
    final public void addHeaderView(View v) {
        throw new RuntimeException("Headers are not supported with TouchListView");
    }

    @Override
    final public void addFooterView(View v, Object data, boolean isSelectable) {
        if (mRemoveMode == SLIDE_LEFT || mRemoveMode == SLIDE_RIGHT) {
            throw new RuntimeException("Footers are not supported with TouchListView in conjunction with remove_mode");
        }
    }

    @Override
    final public void addFooterView(View v) {
        if (mRemoveMode == SLIDE_LEFT || mRemoveMode == SLIDE_RIGHT) {
            throw new RuntimeException("Footers are not supported with TouchListView in conjunction with remove_mode");
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (mRemoveListener != null && mGestureDetector == null) {
            if (mRemoveMode == FLING) {
                mGestureDetector = new GestureDetector(getContext(), new SimpleOnGestureListener() {
                    @Override
                    public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX,
                                           float velocityY) {
                        if (mDragView != null) {
                            if (velocityX > 1000) {
                                Rect r = mTempRect;
                                mDragView.getDrawingRect(r);
                                if (e2.getX() > r.right * 2 / 3) {
                                    // fast fling right with release near the right edge of the screen
                                    stopDragging();
                                    mRemoveListener.remove(mFirstDragPos);
                                    unExpandViews(true);
                                }
                            }
                            // flinging while dragging should have no effect
                            return true;
                        }
                        return false;
                    }
                });
            }
        }
        if (mDragListener != null || mDropListener != null) {
            switch (ev.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    int x = (int) ev.getX();
                    int y = (int) ev.getY();
                    int itemnum = pointToPosition(x, y);
                    if (itemnum == AdapterView.INVALID_POSITION) {
                        break;
                    }

                    View item = (View) getChildAt(itemnum - getFirstVisiblePosition());

                    if (isDraggableRow(item)) {
                        mDragPoint = y - item.getTop();
                        mCoordOffset = ((int) ev.getRawY()) - y;
                        View dragger = item.findViewById(grabberId);
                        Rect r = mTempRect;
//										dragger.getDrawingRect(r);

                        r.left = dragger.getLeft();
                        r.right = dragger.getRight();
                        r.top = dragger.getTop();
                        r.bottom = dragger.getBottom();

                        if ((r.left < x) && (x < r.right)) {
                            item.setDrawingCacheEnabled(true);
                            // Create a copy of the drawing cache so that it does not get recycled
                            // by the framework when the list tries to clean up memory
                            Bitmap bitmap = Bitmap.createBitmap(item.getDrawingCache());
                            item.setDrawingCacheEnabled(false);

                            Rect listBounds = new Rect();

                            getGlobalVisibleRect(listBounds, null);

                            startDragging(bitmap, listBounds.left, y);
                            mDragPos = itemnum;
                            mFirstDragPos = mDragPos;
                            mHeight = getHeight();
                            int touchSlop = mTouchSlop;
                            mUpperBound = Math.min(y - touchSlop, mHeight / 3);
                            mLowerBound = Math.max(y + touchSlop, mHeight * 2 / 3);
                            return false;
                        }

                        mDragView = null;
                    }

                    break;
            }
        }
        return super.onInterceptTouchEvent(ev);
    }

    protected boolean isDraggableRow(View view) {
        return (view.findViewById(grabberId) != null);
    }

    /*
      * pointToPosition() doesn't consider invisible views, but we
      * need to, so implement a slightly different version.
      */
    private int myPointToPosition(int x, int y) {
        Rect frame = mTempRect;
        final int count = getChildCount();
        for (int i = count - 1; i >= 0; i--) {
            final View child = getChildAt(i);
            child.getHitRect(frame);
            if (frame.contains(x, y)) {
                return getFirstVisiblePosition() + i;
            }
        }
        return INVALID_POSITION;
    }

    private int getItemForPosition(int y) {
        int adjustedy = y - mDragPoint - (mItemHeightNormal / 2);
        int pos = myPointToPosition(0, adjustedy);
        if (pos >= 0) {
            if (pos <= mFirstDragPos) {
                pos += 1;
            }
        } else if (adjustedy < 0) {
            pos = 0;
        }
        return pos;
    }

    private void adjustScrollBounds(int y) {
        if (y >= mHeight / 3) {
            mUpperBound = mHeight / 3;
        }
        if (y <= mHeight * 2 / 3) {
            mLowerBound = mHeight * 2 / 3;
        }
    }

    /*
      * Restore size and visibility for all listitems
      */
    private void unExpandViews(boolean deletion) {
        for (int i = 0; ; i++) {
            View v = getChildAt(i);
            if (v == null) {
                if (deletion) {
                    // HACK force update of mItemCount
                    int position = getFirstVisiblePosition();
                    int y = getChildAt(0).getTop();
                    setAdapter(getAdapter());
                    setSelectionFromTop(position, y);
                    // end hack
                }
                layoutChildren(); // force children to be recreated where needed
                v = getChildAt(i);
                if (v == null) {
                    break;
                }
            }

            if (isDraggableRow(v)) {
                ViewGroup.LayoutParams params = v.getLayoutParams();
                params.height = mItemHeightNormal;
                v.setLayoutParams(params);
                v.setVisibility(View.VISIBLE);
            }
        }
    }

    /* Adjust visibility and size to make it appear as though
      * an item is being dragged around and other items are making
      * room for it:
      * If dropping the item would result in it still being in the
      * same place, then make the dragged listitem's size normal,
      * but make the item invisible.
      * Otherwise, if the dragged listitem is still on screen, make
      * it as small as possible and expand the item below the insert
      * point.
      * If the dragged item is not on screen, only expand the item
      * below the current insertpoint.
      */
    private void doExpansion() {
        //drag position after taking into account the list may be scrolled
        int actualDragPos = mDragPos - getFirstVisiblePosition();

        if (mDragPos > mFirstDragPos) {
            actualDragPos++; //wtf?
        }

        int firstDragPosIndexWithOffset = mFirstDragPos - getFirstVisiblePosition();
        View firstDragPosWithOffset = getChildAt(firstDragPosIndexWithOffset);  //may be null if off screen

        //loop through all visible list views so we can hide appropriate rows
        for (int i = 0; ; i++) {
            View loopChild = getChildAt(i);
            if (loopChild == null) {
                break;
            }

            //by default we will set this to be normal height and vis
            int height = mItemHeightNormal;
            int visibility = View.VISIBLE;

            //check if loop child is where the drag started
            if (loopChild.equals(firstDragPosWithOffset)) {
                // as yes check if the current drag is over the drag start position
                if (mDragPos == mFirstDragPos) {
                    // hovering over the original location
                    visibility = View.INVISIBLE;
                } else {
                    // not hovering over it
                    height = 1; //TODO why 1?
                }
            } else if (i == actualDragPos) { //this is the current drag position

                if (mDragPos < getCount() - 1) {
                    height = mItemHeightExpanded;
                }
            }

            if (isDraggableRow(loopChild)) {
                ViewGroup.LayoutParams params = loopChild.getLayoutParams();
                params.height = height;
                loopChild.setLayoutParams(params);
                loopChild.setVisibility(visibility);
            }
        }
        // Request re-layout since we changed the items layout
        // and not doing this would cause bogus hitbox calculation
        // in myPointToPosition
        layoutChildren();
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (mGestureDetector != null) {
            mGestureDetector.onTouchEvent(ev);
        }
        if ((mDragListener != null || mDropListener != null) && mDragView != null) {
            int action = ev.getAction();
            switch (action) {
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    Rect r = mTempRect;
                    mDragView.getDrawingRect(r);
                    stopDragging();

                    if (mRemoveMode == SLIDE_RIGHT && ev.getX() > r.left + (r.width() * 3 / 4)) {
                        if (mRemoveListener != null) {
                            mRemoveListener.remove(mFirstDragPos);
                        }
                        unExpandViews(true);
                    } else if (mRemoveMode == SLIDE_LEFT && ev.getX() < r.left + (r.width() / 4)) {
                        if (mRemoveListener != null) {
                            mRemoveListener.remove(mFirstDragPos);
                        }
                        unExpandViews(true);
                    } else {
                        if (mDropListener != null && mDragPos >= 0 && mDragPos < getCount()) {
                            mDropListener.drop(mFirstDragPos, mDragPos);
                        }
                        unExpandViews(false);
                    }
                    break;

                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_MOVE:
                    int x = (int) ev.getX();
                    int y = (int) ev.getY();
                    dragView(x, y);
                    int itemnum = getItemForPosition(y);
                    if (itemnum >= 0) {
                        if (action == MotionEvent.ACTION_DOWN || itemnum != mDragPos) {
                            if (mDragListener != null) {
                                mDragListener.drag(mDragPos, itemnum);
                            }
                            mDragPos = itemnum;

                            //dont do expansion onDown - this will be done when the dragView is drawn for the first
                            //time to avoid flicker
                            if(action != MotionEvent.ACTION_DOWN){
                                doExpansion();
                            }
                        }
                        int speed = 0;
                        adjustScrollBounds(y);
                        if (y > mLowerBound) {
                            // scroll the list up a bit
                            speed = y > (mHeight + mLowerBound) / 2 ? 16 : 4;
                        } else if (y < mUpperBound) {
                            // scroll the list down a bit
                            speed = y < mUpperBound / 2 ? -16 : -4;
                        }
                        if (speed != 0) {
                            int ref = pointToPosition(0, mHeight / 2);
                            if (ref == AdapterView.INVALID_POSITION) {
                                //we hit a divider or an invisible view, check somewhere else
                                ref = pointToPosition(0, mHeight / 2 + getDividerHeight() + 64);
                            }
                            View v = getChildAt(ref - getFirstVisiblePosition());
                            if (v != null) {
                                int pos = v.getTop();
                                setSelectionFromTop(ref, pos - speed);
                            }
                        }
                    }
                    break;
            }
            return true;
        }
        return super.onTouchEvent(ev);
    }

    private void startDragging(Bitmap bm, int x, int y) {
        stopDragging();

        mWindowParams = new WindowManager.LayoutParams();
        mWindowParams.gravity = Gravity.TOP | Gravity.LEFT;
        mWindowParams.x = x;
        mWindowParams.y = y - mDragPoint + mCoordOffset;

        mWindowParams.height = WindowManager.LayoutParams.WRAP_CONTENT;
        mWindowParams.width = WindowManager.LayoutParams.WRAP_CONTENT;
        mWindowParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        mWindowParams.format = PixelFormat.TRANSLUCENT;
        mWindowParams.windowAnimations = 0;

        //use img view wrapper to avoid flicker (see class doc)
        ImageView v = new ImageViewWrapper(getContext(), new Runnable() {
            @Override
            public void run() {
                doExpansion();
            }
        });

        v.setBackgroundColor(dragndropBackgroundColor);
        v.setImageBitmap(bm);
        mDragBitmap = bm;

        mWindowManager = (WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE);
        mWindowManager.addView(v, mWindowParams);
        mDragView = v;
    }

    private void dragView(int x, int y) {
        float alpha = 1.0f;
        int width = mDragView.getWidth();

        if (mRemoveMode == SLIDE_RIGHT) {
            if (x > width / 2) {
                alpha = ((float) (width - x)) / (width / 2);
            }
            mWindowParams.alpha = alpha;
        } else if (mRemoveMode == SLIDE_LEFT) {
            if (x < width / 2) {
                alpha = ((float) x) / (width / 2);
            }
            mWindowParams.alpha = alpha;
        }
        Log.d("cwac", "alpha:" + alpha);
        mWindowParams.y = y - mDragPoint + mCoordOffset;
        mWindowManager.updateViewLayout(mDragView, mWindowParams);
    }

    private void stopDragging() {
        if (mDragView != null) {
            WindowManager wm = (WindowManager) getContext().getSystemService("window");
            wm.removeView(mDragView);
            mDragView.setImageDrawable(null);
            mDragView = null;
        }
        if (mDragBitmap != null) {
            mDragBitmap.recycle();
            mDragBitmap = null;
        }
    }

    public void setDragListener(DragListener l) {
        mDragListener = l;
    }

    public void setDropListener(DropListener l) {
        mDropListener = l;
    }

    public void setRemoveListener(RemoveListener l) {
        mRemoveListener = l;
    }

    public interface DragListener {
        void drag(int from, int to);
    }

    public interface DropListener {
        void drop(int from, int to);
    }

    public interface RemoveListener {
        void remove(int which);
    }

    /**
     * Quick fix. This wrapper will execute the passed in runnable the first time its drawn. When adding a view to
     * the WindowManager it seems to have a short delay before being drawn. I have tried invalidating the view and
     * also the root view of the window bit neither seems to work :(
     */
    private static class ImageViewWrapper extends ImageView{

        private Runnable mRunnable;

        /**
         * @param context
         * @param runnable execute on first draw - put any hiding view logic in here
         */
        public ImageViewWrapper(Context context, Runnable runnable) {
            super(context);
            mRunnable = runnable;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            if(mRunnable != null){
                mRunnable.run();
                mRunnable = null;
            }
        }
    }
}

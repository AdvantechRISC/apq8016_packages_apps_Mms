/*
 * Copyright (C) 2008 Esmertec AG.
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.mms.ui;

import java.util.regex.Pattern;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.drm.DrmStore;
import android.net.Uri;
import android.provider.Telephony.Mms;
import android.provider.Telephony.MmsSms;
import android.provider.Telephony.Sms;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;

import com.android.mms.LogTag;
import com.android.mms.MmsApp;
import com.android.mms.MmsConfig;
import com.android.mms.R;
import com.android.mms.data.Contact;
import com.android.mms.data.WorkingMessage;
import com.android.mms.drm.DrmUtils;
import com.android.mms.model.LayoutModel;
import com.android.mms.model.SlideModel;
import com.android.mms.model.SlideshowModel;
import com.android.mms.model.TextModel;
import com.android.mms.rcs.RcsUtils;
import com.android.mms.ui.MessageListAdapter.ColumnsMap;
import com.android.mms.util.AddressUtils;
import com.android.mms.util.DownloadManager;
import com.android.mms.util.ItemLoadedCallback;
import com.android.mms.util.ItemLoadedFuture;
import com.android.mms.util.PduLoaderManager;
import com.google.android.mms.ContentType;
import com.google.android.mms.MmsException;
import com.google.android.mms.pdu.EncodedStringValue;
import com.google.android.mms.pdu.MultimediaMessagePdu;
import com.google.android.mms.pdu.NotificationInd;
import com.google.android.mms.pdu.PduBody;
import com.google.android.mms.pdu.PduHeaders;
import com.google.android.mms.pdu.PduPart;
import com.google.android.mms.pdu.PduPersister;
import com.google.android.mms.pdu.RetrieveConf;
import com.google.android.mms.pdu.SendReq;

import com.suntek.mway.rcs.client.aidl.constant.Constants;
/**
 * Mostly immutable model for an SMS/MMS message.
 *
 * <p>The only mutable field is the cached formatted message member,
 * the formatting of which is done outside this model in MessageListItem.
 */
public class MessageItem {
    private static String TAG = LogTag.TAG;

    public enum DeliveryStatus  { NONE, INFO, FAILED, PENDING, RECEIVED }

    public static int ATTACHMENT_TYPE_NOT_LOADED = -1;

    private final static int CDMA_STATUS_SHIFT = 16;

    final Context mContext;
    final String mType;
    final long mMsgId;
    final int mBoxId;

    DeliveryStatus mDeliveryStatus;
    boolean mReadReport;
    boolean mLocked;            // locked to prevent auto-deletion

    String mTimestamp;
    String mAddress;
    String mContact;
    String mBody; // Body of SMS, first text of MMS.
    int mSubId;   // Holds current mms/sms sub Id value.
    String mTextContentType; // ContentType of text of MMS.
    Pattern mHighlight; // portion of message to highlight (from search)

    // The only non-immutable field.  Not synchronized, as access will
    // only be from the main GUI thread.  Worst case if accessed from
    // another thread is it'll return null and be set again from that
    // thread.
    CharSequence mCachedFormattedMessage;

    // The last message is cached above in mCachedFormattedMessage. In the latest design, we
    // show "Sending..." in place of the timestamp when a message is being sent. mLastSendingState
    // is used to keep track of the last sending state so that if the current sending state is
    // different, we can clear the message cache so it will get rebuilt and recached.
    boolean mLastSendingState;

    // Fields for MMS only.
    Uri mMessageUri;
    int mMessageType;
    int mAttachmentType;
    String mSubject;
    SlideshowModel mSlideshow;
    int mMessageSize;
    int mErrorType;
    int mErrorCode;
    int mMmsStatus;
    Cursor mCursor;
    ColumnsMap mColumnsMap;
    private PduLoadedCallback mPduLoadedCallback;
    private ItemLoadedFuture mItemLoadedFuture;
    int mLayoutType = LayoutModel.DEFAULT_LAYOUT_TYPE;
    long mDate;
    boolean mIsForwardable = true;
    boolean mHasAttachmentToSave = false;
    boolean mIsDrmRingtoneWithRights = false;

    /* Begin add for RCS */
    int mCountDown = 0;
    String mRcsPath;
    String mRcsThumbPath;
    int mRcsType;
    boolean mIsRcsBurnMessage;
    int mRcsIsDownload;
    int mRcsMsgState;
    int mRcsFileSize;
    int mRcsChatType;
    String mRcsMessageId;
    String mRcsMimeType;

    /* End add for RCS */

    MessageItem(Context context, String type, final Cursor cursor,
            final ColumnsMap columnsMap, Pattern highlight) throws MmsException {
        mContext = context;
        mMsgId = cursor.getLong(columnsMap.mColumnMsgId);
        mHighlight = highlight;
        mType = type;
        mCursor = cursor;
        mColumnsMap = columnsMap;

        if ("sms".equals(type)) {
            mReadReport = false; // No read reports in sms

            long status = cursor.getLong(columnsMap.mColumnSmsStatus);
            // If the 31-16 bits is not 0, means this is a CDMA sms.
            if ((status >> CDMA_STATUS_SHIFT) > 0) {
                status = status >> CDMA_STATUS_SHIFT;
            }
            if (status == Sms.STATUS_NONE) {
                // No delivery report requested
                mDeliveryStatus = DeliveryStatus.NONE;
            } else if (status >= Sms.STATUS_FAILED) {
                // Failure
                mDeliveryStatus = DeliveryStatus.FAILED;
            } else if (status >= Sms.STATUS_PENDING) {
                // Pending
                mDeliveryStatus = DeliveryStatus.PENDING;
            } else {
                // Success
                mDeliveryStatus = DeliveryStatus.RECEIVED;
            }

            mMessageUri = ContentUris.withAppendedId(Sms.CONTENT_URI, mMsgId);
            // Set contact and message body
            mBoxId = cursor.getInt(columnsMap.mColumnSmsType);
            mAddress = cursor.getString(columnsMap.mColumnSmsAddress);
            if (Sms.isOutgoingFolder(mBoxId)) {
                String meString = context.getString(
                        R.string.messagelist_sender_self);

                mContact = meString;
            } else {
                // For incoming messages, the ADDRESS field contains the sender.
                mContact = Contact.get(mAddress, false).getName();
            }
            if (MmsConfig.isRcsVersion()) {
                mRcsPath = cursor.getString(columnsMap.mColumnRcsPath);
                mRcsThumbPath = cursor.getString(columnsMap.mColumnRcsThumbPath);
                mRcsType = cursor.getInt(columnsMap.mColumnRcsMsgType);
                mIsRcsBurnMessage = (cursor.getInt(columnsMap.mColumnRcsBurnMessage)
                        > RcsUtils.RCS_NOT_A_BURN_MESSAGE);
                mRcsIsDownload = cursor.getInt(columnsMap.mColumnRcsIsDownload);
                mRcsMsgState = cursor.getInt(columnsMap.mColumnRcsMsgState);
                mRcsMimeType = cursor.getString(columnsMap.mColumnRcsMimeType);
                mRcsFileSize= cursor.getInt(columnsMap.mColumnRcsFileSize);
                mRcsChatType = cursor.getInt(columnsMap.mColumnRcsChatType);
                mRcsMessageId = cursor.getString(columnsMap.mColumnRcsMessageId);
            }
            mBody = cursor.getString(columnsMap.mColumnSmsBody);

            mSubId = cursor.getInt(columnsMap.mColumnSubId);
            // Unless the message is currently in the progress of being sent, it gets a time stamp.
            if (!isOutgoingMessage()) {
                if (mBoxId == Sms.MESSAGE_TYPE_SENT) {
                    // Set "sent" time stamp
                    mDate = cursor.getLong(columnsMap.mColumnSmsDate);
                    //cdma sms stored in UIM card don not have timestamp
                    if (0 == mDate) {
                        mDate = System.currentTimeMillis();
                    }
                    mTimestamp = formatTimeStamp(context, true, mDate);
                } else {
                    // Set "received" time stamp
                    mDate = cursor.getLong(context.getResources().getBoolean(
                                R.bool.config_display_sent_time) ? columnsMap.mColumnSmsDateSent
                                : columnsMap.mColumnSmsDate);
                    //cdma sms stored in UIM card don not have timestamp
                    if (0 == mDate) {
                        mDate = System.currentTimeMillis();
                    }
                    mTimestamp = formatTimeStamp(context, false, mDate);
                }
            }

            mLocked = cursor.getInt(columnsMap.mColumnSmsLocked) != 0;
            mErrorCode = cursor.getInt(columnsMap.mColumnSmsErrorCode);
        } else if ("mms".equals(type)) {
            mMessageUri = ContentUris.withAppendedId(Mms.CONTENT_URI, mMsgId);
            mBoxId = cursor.getInt(columnsMap.mColumnMmsMessageBox);
            mMessageType = cursor.getInt(columnsMap.mColumnMmsMessageType);
            mErrorType = cursor.getInt(columnsMap.mColumnMmsErrorType);
            String subject = cursor.getString(columnsMap.mColumnMmsSubject);
            mSubId = cursor.getInt(columnsMap.mColumnSubId);

            if (!TextUtils.isEmpty(subject)) {
                EncodedStringValue v = new EncodedStringValue(
                        cursor.getInt(columnsMap.mColumnMmsSubjectCharset),
                        PduPersister.getBytes(subject));
                mSubject = MessageUtils.cleanseMmsSubject(context, v.getString());
            }
            mLocked = cursor.getInt(columnsMap.mColumnMmsLocked) != 0;
            mSlideshow = null;
            mDeliveryStatus = DeliveryStatus.NONE;
            mReadReport = false;
            mBody = null;
            mMessageSize = 0;
            mTextContentType = null;
            // Initialize the time stamp to "" instead of null
            mTimestamp = "";
            mMmsStatus = cursor.getInt(columnsMap.mColumnMmsStatus);
            mAttachmentType = cursor.getInt(columnsMap.mColumnMmsTextOnly) != 0 ?
                    WorkingMessage.TEXT : ATTACHMENT_TYPE_NOT_LOADED;

            // Start an async load of the pdu. If the pdu is already loaded, the callback
            // will get called immediately
            boolean loadSlideshow = mMessageType != PduHeaders.MESSAGE_TYPE_NOTIFICATION_IND;

            mItemLoadedFuture = MmsApp.getApplication().getPduLoaderManager()
                    .getPdu(mMessageUri, loadSlideshow,
                    new PduLoadedMessageItemCallback());

        } else {
            throw new MmsException("Unknown type of the message: " + type);
        }
    }

    private String formatTimeStamp(Context context, boolean isSent, long timestamp) {
        if (context.getResources().getBoolean(R.bool.config_display_sent_time)) {
            return MessageUtils.formatTimeStampString(context, timestamp);
        } else {
            return String.format(context.getString(isSent ? R.string.sent_on
                    : R.string.received_on), MessageUtils.formatTimeStampString(context,
                    timestamp));
        }
    }

    public boolean isCdmaInboxMessage() {
        int activePhone;
        if (MessageUtils.isMultiSimEnabledMms()) {
            activePhone = TelephonyManager.getDefault().getCurrentPhoneType(mSubId);
        } else {
            activePhone = TelephonyManager.getDefault().getPhoneType();
        }

        return ((mBoxId == Sms.MESSAGE_TYPE_INBOX
                || mBoxId == Sms.MESSAGE_TYPE_ALL)
                && (TelephonyManager.PHONE_TYPE_CDMA == activePhone));
    }

    private void interpretFrom(EncodedStringValue from, Uri messageUri) {
        if (from != null) {
            mAddress = from.getString();
        } else {
            // In the rare case when getting the "from" address from the pdu fails,
            // (e.g. from == null) fall back to a slower, yet more reliable method of
            // getting the address from the "addr" table. This is what the Messaging
            // notification system uses.
            mAddress = AddressUtils.getFrom(mContext, messageUri);
        }
        mContact = TextUtils.isEmpty(mAddress) ? "" : Contact.get(mAddress, false).getName();
    }

    public boolean isMms() {
        return mType.equals("mms");
    }

    public boolean isSms() {
        return mType.equals("sms");
    }

    public boolean isDownloaded() {
        return (mMessageType != PduHeaders.MESSAGE_TYPE_NOTIFICATION_IND);
    }

    public boolean isMe() {
        // Logic matches MessageListAdapter.getItemViewType which is used to decide which
        // type of MessageListItem to create: a left or right justified item depending on whether
        // the message is incoming or outgoing.
        boolean isIncomingMms = isMms()
                                    && (mBoxId == Mms.MESSAGE_BOX_INBOX
                                            || mBoxId == Mms.MESSAGE_BOX_ALL);
        boolean isIncomingSms = isSms()
                                    && (mBoxId == Sms.MESSAGE_TYPE_INBOX
                                            || mBoxId == Sms.MESSAGE_TYPE_ALL);
        return !(isIncomingMms || isIncomingSms);
    }

    public boolean isOutgoingMessage() {
        boolean isOutgoingMms = isMms() && (mBoxId == Mms.MESSAGE_BOX_OUTBOX);
        boolean isOutgoingSms = isSms()
                                    && ((mBoxId == Sms.MESSAGE_TYPE_FAILED)
                                            || (mBoxId == Sms.MESSAGE_TYPE_OUTBOX)
                                            || (mBoxId == Sms.MESSAGE_TYPE_QUEUED));
        return isOutgoingMms || isOutgoingSms;
    }

    public boolean isSending() {
        return !isFailedMessage() && isOutgoingMessage();
    }

    public boolean isFailedMessage() {
        boolean isFailedMms = isMms()
                            && (mErrorType >= MmsSms.ERR_TYPE_GENERIC_PERMANENT
                            || (mErrorType == MmsSms.ERR_TYPE_MMS_PROTO_TRANSIENT
                && mContext.getResources().getBoolean(R.bool.config_manual_resend)));
        boolean isFailedSms = isSms()
                            && (mBoxId == Sms.MESSAGE_TYPE_FAILED);
        return isFailedMms || isFailedSms;
    }

    // Note: This is the only mutable field in this class.  Think of
    // mCachedFormattedMessage as a C++ 'mutable' field on a const
    // object, with this being a lazy accessor whose logic to set it
    // is outside the class for model/view separation reasons.  In any
    // case, please keep this class conceptually immutable.
    public void setCachedFormattedMessage(CharSequence formattedMessage) {
        mCachedFormattedMessage = formattedMessage;
    }

    public CharSequence getCachedFormattedMessage() {
        boolean isSending = isSending();
        if (isSending != mLastSendingState) {
            mLastSendingState = isSending;
            mCachedFormattedMessage = null;         // clear cache so we'll rebuild the message
                                                    // to show "Sending..." or the sent date.
        }
        return mCachedFormattedMessage;
    }

    public int getBoxId() {
        return mBoxId;
    }

    public long getMessageId() {
        return mMsgId;
    }

    public boolean hasAttachemntToSave(){
        return mHasAttachmentToSave;
    }

    public int getMmsDownloadStatus() {
        return MessageUtils.getMmsDownloadStatus(mMmsStatus);
    }

    @Override
    public String toString() {
        return "type: " + mType +
            " box: " + mBoxId +
            " uri: " + mMessageUri +
            " address: " + mAddress +
            " contact: " + mContact +
            " read: " + mReadReport +
            " delivery status: " + mDeliveryStatus;
    }

    private boolean isContentTypeWorthToSave(String type) {
        return ContentType.isImageType(type) || ContentType.isVideoType(type)
                || ContentType.isAudioType(type) || DrmUtils.isDrmType(type)
                || type.equals(ContentType.AUDIO_OGG.toLowerCase())
                || type.equals(ContentType.TEXT_VCARD.toLowerCase());
    }

    private void isAttachmentSaveable(PduBody body) {
        for (int i = 0; i < body.getPartsNum(); i++) {
            PduPart part = body.getPart(i);
            String type = (new String(part.getContentType())).toLowerCase();
            if (isContentTypeWorthToSave(type)) {
                mHasAttachmentToSave = true;
            }
        }
    }

    private void isDrmRingtoneWithRights(PduBody body) {
        for (int i = 0; i < body.getPartsNum(); i++) {
            PduPart part = body.getPart(i);
            String type = (new String(part.getContentType())).toLowerCase();
            if (DrmUtils.isDrmType(type)) {
                String mimeType = MmsApp.getApplication().getDrmManagerClient()
                        .getOriginalMimeType(part.getDataUri());
                if (ContentType.isAudioType(mimeType)
                        && DrmUtils.haveRightsForAction(part.getDataUri(),
                                DrmStore.Action.RINGTONE)) {
                    mIsDrmRingtoneWithRights = true;
                }
            }
        }
    }

    private void isForwardable(PduBody body) {
        for (int i = 0; i < body.getPartsNum(); i++) {
            PduPart part = body.getPart(i);
            String type = (new String(part.getContentType())).toLowerCase();
            if (DrmUtils.isDrmType(type)
                    && (!DrmUtils.haveRightsForAction(part.getDataUri(),
                            DrmStore.Action.TRANSFER))) {
                mIsForwardable = false;
            }
        }
    }

    public class PduLoadedMessageItemCallback implements ItemLoadedCallback {
        public void onItemLoaded(Object result, Throwable exception) {
            if (exception != null) {
                Log.e(TAG, "PduLoadedMessageItemCallback PDU couldn't be loaded: ", exception);
                return;
            }
            if (mItemLoadedFuture != null) {
                synchronized(mItemLoadedFuture) {
                    mItemLoadedFuture.setIsDone(true);
                }
            }
            PduLoaderManager.PduLoaded pduLoaded = (PduLoaderManager.PduLoaded)result;
            long timestamp = 0L;
            if (PduHeaders.MESSAGE_TYPE_NOTIFICATION_IND == mMessageType) {
                mDeliveryStatus = DeliveryStatus.NONE;
                NotificationInd notifInd = (NotificationInd)pduLoaded.mPdu;
                interpretFrom(notifInd.getFrom(), mMessageUri);
                // Borrow the mBody to hold the URL of the message.
                mBody = new String(notifInd.getContentLocation());
                mMessageSize = (int) notifInd.getMessageSize();
                timestamp = notifInd.getExpiry() * 1000L;
            } else {
                if (mCursor.isClosed()) {
                    return;
                }
                MultimediaMessagePdu msg = (MultimediaMessagePdu)pduLoaded.mPdu;
                mSlideshow = pduLoaded.mSlideshow;
                mAttachmentType = MessageUtils.getAttachmentType(mSlideshow, msg);
                if (mSlideshow != null && mSlideshow.getLayout() != null) {
                    mLayoutType = mSlideshow.getLayout().getLayoutType();
                }

                if (mMessageType == PduHeaders.MESSAGE_TYPE_RETRIEVE_CONF) {
                    if (msg == null) {
                        interpretFrom(null, mMessageUri);
                    } else {
                        RetrieveConf retrieveConf = (RetrieveConf) msg;
                        interpretFrom(retrieveConf.getFrom(), mMessageUri);
                        timestamp = retrieveConf.getDate() * 1000L;
                    }
                } else {
                    // Use constant string for outgoing messages
                    mContact = mAddress =
                            mContext.getString(R.string.messagelist_sender_self);
                    timestamp = msg == null ? 0 : ((SendReq) msg).getDate() * 1000L;
                }

                SlideModel slide = mSlideshow == null ? null : mSlideshow.get(0);
                if ((slide != null) && slide.hasText()) {
                    TextModel tm = slide.getText();
                    mBody = tm.getText();
                    mTextContentType = tm.getContentType();
                }

                mMessageSize = mSlideshow == null ? 0 : mSlideshow.getTotalMessageSize();

                String report = mCursor.getString(mColumnsMap.mColumnMmsDeliveryReport);
                if ((report == null) || !mAddress.equals(mContext.getString(
                        R.string.messagelist_sender_self))) {
                    mDeliveryStatus = DeliveryStatus.NONE;
                } else {
                    int reportInt;
                    try {
                        reportInt = Integer.parseInt(report);
                        if (reportInt == PduHeaders.VALUE_YES) {
                            mDeliveryStatus = checkDeliveryStatus();
                        } else {
                            mDeliveryStatus = DeliveryStatus.NONE;
                        }
                    } catch (NumberFormatException nfe) {
                        Log.e(TAG, "Value for delivery report was invalid.");
                        mDeliveryStatus = DeliveryStatus.NONE;
                    }
                }

                report = mCursor.getString(mColumnsMap.mColumnMmsReadReport);
                if ((report == null) || !mAddress.equals(mContext.getString(
                        R.string.messagelist_sender_self))) {
                    mReadReport = false;
                } else {
                    int reportInt;
                    try {
                        reportInt = Integer.parseInt(report);
                        mReadReport = (reportInt == PduHeaders.VALUE_YES);
                    } catch (NumberFormatException nfe) {
                        Log.e(TAG, "Value for read report was invalid.");
                        mReadReport = false;
                    }
                }
                PduBody body = msg.getBody();
                if (body != null) {
                    isAttachmentSaveable(body);
                    isDrmRingtoneWithRights(body);
                    isForwardable(body);
                }
            }
            if (!isOutgoingMessage()) {
                if (PduHeaders.MESSAGE_TYPE_NOTIFICATION_IND == mMessageType) {
                    mTimestamp = mContext.getString(R.string.expire_on,
                            MessageUtils.formatTimeStampString(mContext, timestamp));
                } else {
                    // add judgement the Mms is sent or received and format mTimestamp
                    mTimestamp = formatTimeStamp(mContext, mBoxId == Mms.MESSAGE_BOX_SENT,
                            timestamp);
                }
            }
            if (mPduLoadedCallback != null) {
                mPduLoadedCallback.onPduLoaded(MessageItem.this);
            }
        }
    }

    private DeliveryStatus checkDeliveryStatus() {
        String[] project = {Mms.MESSAGE_ID};
        Cursor c = mContext.getContentResolver().query(mMessageUri, project, null, null, null);
        try{
            if (c != null && c.moveToFirst()) {
                String m_id = c.getString(0);
                if (m_id != null) {
                    String where = Mms.MESSAGE_ID + "=? and " + Mms.MESSAGE_TYPE + "=?";
                    String[] whereValue = {m_id,
                            Integer.toString(PduHeaders.MESSAGE_TYPE_DELIVERY_IND)};
                    Cursor cur = mContext.getContentResolver().query(
                            Mms.CONTENT_URI, project, where, whereValue, null);
                    try{
                        if (cur != null && cur.getCount() > 0) {
                            return DeliveryStatus.RECEIVED;
                        } else {
                            return DeliveryStatus.PENDING;
                        }
                    } finally {
                        if (cur != null) {
                            cur.close();
                        }
                    }
                }
            }
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return DeliveryStatus.PENDING;
    }


    public void setOnPduLoaded(PduLoadedCallback pduLoadedCallback) {
        mPduLoadedCallback = pduLoadedCallback;
    }

    public void cancelPduLoading() {
        if (mItemLoadedFuture != null && !mItemLoadedFuture.isDone()) {
            if (Log.isLoggable(LogTag.APP, Log.DEBUG)) {
                Log.v(TAG, "cancelPduLoading for: " + this);
            }
            mItemLoadedFuture.cancel(mMessageUri);
        }
        mItemLoadedFuture = null;
    }

    public interface PduLoadedCallback {
        /**
         * Called when this item's pdu and slideshow are finished loading.
         *
         * @param messageItem the MessageItem that finished loading.
         */
        void onPduLoaded(MessageItem messageItem);
    }

    public SlideshowModel getSlideshow() {
        return mSlideshow;
    }

    /* Begin add for RCS */
    public String getTimestamp() {
        return mTimestamp;
    }

    public void setTimestamp(String timesTamp) {
        mTimestamp = timesTamp;
    }

    public void setDate(long date) {
        mDate = date;
    }

    public long getDate() {
        return mDate;
    }

    public String getMsgBody() {
        return mBody;
    }

    public void setMsgBody(String body) {
        mBody = body;
    }

    public String getRcsPath() {
        return mRcsPath;
    }

    public String getRcsThumbPath() {
        return mRcsThumbPath;
    }

    public void setRcsThumbPath(String thumbPath) {
        this.mRcsThumbPath = thumbPath;
    }

    public int getRcsMsgType() {
        return mRcsType;
    }

    public boolean isRcsBurnMessage() {
        return mIsRcsBurnMessage;
    }

    public int getMsgDownlaodState() {
        return mRcsIsDownload;
    }

    public int getRcsMsgState() {
        return mRcsMsgState;
    }

    public int getRcsMsgFileSize() {
        return mRcsFileSize;
    }

    public String getRcsMimeType() {
        return mRcsMimeType;
    }

    public int getRcsChatType() {
        return mRcsChatType;
    }

    /**
     *@return whether this message is a RCS message.
     */
    public boolean isRcsMessage() {
        switch (mRcsChatType) {
            case RcsUtils.RCS_CHAT_TYPE_ONE_TO_ONE:
            case RcsUtils.RCS_CHAT_TYPE_ONE_TO_N:
            case RcsUtils.RCS_CHAT_TYPE_GROUP_CHAT:
            case RcsUtils.RCS_CHAT_TYPE_TO_PC:
                return true;
            default:
                return false;
        }
    }

    public boolean isRcsMediaMsg() {
        if (mRcsType == RcsUtils.RCS_MSG_TYPE_IMAGE
                || mRcsType == RcsUtils.RCS_MSG_TYPE_AUDIO
                || mRcsType == RcsUtils.RCS_MSG_TYPE_VIDEO) {
            return true;
        } else {
            return false;
        }
    }

    public boolean isRcsAudioMessage(){
        if (mRcsType == RcsUtils.RCS_MSG_TYPE_AUDIO) {
            return true;
        } else {
            return false;
        }
    }

    public boolean isGroupChatMessageWithoutNotification() {
        return mRcsChatType == Constants.MessageConstants.CONST_CHAT_GROUP
                && mRcsType != Constants.MessageConstants.CONST_MESSAGE_NOTIFICATION;
    }

    public int getCountDown() {
        return mCountDown;
    }

    public void setCountDown(int countDown) {
        this.mCountDown = countDown;
    }

    public boolean isFailedRcsTextMessage() {
        return getRcsMsgState() == RcsUtils.MESSAGE_FAIL
                && getRcsMsgType() == RcsUtils.RCS_MSG_TYPE_TEXT;
    }
    /* End add for RCS */

}

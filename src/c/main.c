#include <pebble.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static Window *s_window;
static BitmapLayer *s_bitmap_layer;
static Layer *s_overlay_layer;
static TextLayer *s_status_layer;

static GBitmap *s_art_bitmap;
static GBitmap *s_placeholder_bitmap;

static uint8_t *s_png;
static uint32_t s_png_size;
static uint16_t s_chunks_total;
static uint16_t s_chunks_got;
static uint32_t s_image_seq;
static uint8_t s_chunk_seen[64];

static char s_time_buf[8];
static char s_date_buf[12];
static char s_battery_buf[8];
static char s_title[49];
static char s_artist[49];
static char s_track_buf[128];
static int32_t s_year;
static bool s_connected;
static bool s_has_live_art;
static int32_t s_duration_ms;
static int32_t s_position_ms;
static bool s_playing;
static AppTimer *s_progress_timer;

#define TRACK_TEXT_H 22
#define PROGRESS_BAR_H 3
#define CHUNK_BYTES 400
#define MAX_CHUNKS 64
#define MAX_PNG 12000
#define INBOX_SIZE 820
#define OUTBOX_SIZE 64

static void update_status_hint(void);
static void overlay_mark_dirty(void);

static uint32_t tuple_u32(Tuple *t) {
  if (!t) {
    return 0;
  }
  if (t->type == TUPLE_INT) {
    return (uint32_t)t->value->int32;
  }
  if (t->length >= 4) {
    return t->value->uint32;
  }
  if (t->length >= 2) {
    return t->value->uint16;
  }
  return t->value->uint8;
}

static void request_refresh(void) {
  DictionaryIterator *iter;
  if (app_message_outbox_begin(&iter) != APP_MSG_OK) {
    return;
  }
  dict_write_uint8(iter, MESSAGE_KEY_RequestRefresh, 1);
  app_message_outbox_send();
}

static void show_bitmap(GBitmap *bmp) {
  if (s_bitmap_layer && bmp) {
    bitmap_layer_set_bitmap(s_bitmap_layer, bmp);
    layer_mark_dirty(bitmap_layer_get_layer(s_bitmap_layer));
  }
}

static void free_png(void) {
  if (s_png) {
    free(s_png);
    s_png = NULL;
  }
  s_png_size = 0;
  s_chunks_total = 0;
  s_chunks_got = 0;
}

static bool all_chunks_present(void) {
  if (s_chunks_total == 0 || s_chunks_total > MAX_CHUNKS) {
    return false;
  }
  for (uint16_t i = 0; i < s_chunks_total; i++) {
    if (!s_chunk_seen[i]) {
      return false;
    }
  }
  return true;
}

static void finalize_png(void) {
  if (!s_png || s_png_size < 16) {
    return;
  }
  GBitmap *decoded = gbitmap_create_from_png_data(s_png, s_png_size);
  if (!decoded) {
    APP_LOG(APP_LOG_LEVEL_ERROR, "png decode failed (%u bytes)", (unsigned)s_png_size);
    return;
  }
  if (s_art_bitmap) {
    gbitmap_destroy(s_art_bitmap);
  }
  s_art_bitmap = decoded;
  s_has_live_art = true;
  show_bitmap(s_art_bitmap);
  update_status_hint();
  APP_LOG(APP_LOG_LEVEL_INFO, "png ready %u bytes", (unsigned)s_png_size);
  free_png();
}

static void handle_image_header(DictionaryIterator *iter) {
  Tuple *size_t = dict_find(iter, MESSAGE_KEY_ImageDataSize);
  Tuple *chunks_t = dict_find(iter, MESSAGE_KEY_ImageChunksTotal);
  Tuple *seq_t = dict_find(iter, MESSAGE_KEY_ImageSeq);
  if (!size_t || !chunks_t) {
    return;
  }

  uint32_t size = tuple_u32(size_t);
  uint16_t total = (uint16_t)tuple_u32(chunks_t);
  uint32_t seq = seq_t ? tuple_u32(seq_t) : (s_image_seq + 1);

  if (size < 16 || size > MAX_PNG || total == 0 || total > MAX_CHUNKS) {
    APP_LOG(APP_LOG_LEVEL_ERROR, "bad png header size=%u chunks=%u", (unsigned)size, total);
    return;
  }

  if (seq == s_image_seq && s_png && s_png_size == size) {
    s_chunks_total = total;
    return;
  }

  free_png();
  s_png = malloc(size);
  if (!s_png) {
    APP_LOG(APP_LOG_LEVEL_ERROR, "png alloc failed");
    return;
  }
  memset(s_png, 0, size);
  memset(s_chunk_seen, 0, sizeof(s_chunk_seen));
  s_png_size = size;
  s_chunks_total = total;
  s_chunks_got = 0;
  s_image_seq = seq;
  APP_LOG(APP_LOG_LEVEL_INFO, "png header %u bytes %u chunks seq=%u",
          (unsigned)size, total, (unsigned)seq);
}

static void handle_image_chunk(DictionaryIterator *iter) {
  Tuple *idx_t = dict_find(iter, MESSAGE_KEY_ImageChunkIndex);
  Tuple *data_t = dict_find(iter, MESSAGE_KEY_ImageChunkData);
  if (!idx_t || !data_t || !s_png) {
    return;
  }
  Tuple *seq_t = dict_find(iter, MESSAGE_KEY_ImageSeq);
  if (seq_t && tuple_u32(seq_t) != s_image_seq) {
    return;
  }

  int index = (int)tuple_u32(idx_t);
  if (index < 0 || index >= MAX_CHUNKS || s_chunk_seen[index]) {
    if (index >= 0 && index < MAX_CHUNKS && s_chunk_seen[index] && all_chunks_present()) {
      finalize_png();
    }
    return;
  }

  uint16_t len = data_t->length;
  uint32_t offset = (uint32_t)index * CHUNK_BYTES;
  if (offset >= s_png_size) {
    return;
  }
  if (offset + len > s_png_size) {
    len = (uint16_t)(s_png_size - offset);
  }
  memcpy(s_png + offset, data_t->value->data, len);
  s_chunk_seen[index] = 1;
  s_chunks_got++;
  if ((s_chunks_got % 8) == 0 || s_chunks_got == s_chunks_total) {
    APP_LOG(APP_LOG_LEVEL_INFO, "png chunk %d (%u/%u)", index, s_chunks_got, s_chunks_total);
  }

  if (all_chunks_present()) {
    finalize_png();
  }
}

static void progress_timer_cb(void *data) {
  s_progress_timer = NULL;
  if (!s_playing || s_duration_ms <= 0) {
    return;
  }
  s_position_ms += 1000;
  if (s_position_ms > s_duration_ms) {
    s_position_ms = s_duration_ms;
  }
  overlay_mark_dirty();
  s_progress_timer = app_timer_register(1000, progress_timer_cb, NULL);
}

static void apply_playback(int32_t position_ms, int32_t duration_ms, bool playing) {
  s_duration_ms = duration_ms > 0 ? duration_ms : 0;
  s_position_ms = position_ms > 0 ? position_ms : 0;
  if (s_duration_ms > 0 && s_position_ms > s_duration_ms) {
    s_position_ms = s_duration_ms;
  }
  s_playing = playing;
  if (s_progress_timer) {
    app_timer_cancel(s_progress_timer);
    s_progress_timer = NULL;
  }
  if (s_playing && s_duration_ms > 0) {
    s_progress_timer = app_timer_register(1000, progress_timer_cb, NULL);
  }
  overlay_mark_dirty();
}

static void update_track_line(void) {
  if (s_artist[0] && s_title[0] && s_year >= 1000) {
    snprintf(s_track_buf, sizeof(s_track_buf), "%s - %s (%d)", s_artist, s_title, (int)s_year);
  } else if (s_artist[0] && s_title[0]) {
    snprintf(s_track_buf, sizeof(s_track_buf), "%s - %s", s_artist, s_title);
  } else if (s_title[0] && s_year >= 1000) {
    snprintf(s_track_buf, sizeof(s_track_buf), "%s (%d)", s_title, (int)s_year);
  } else if (s_title[0]) {
    snprintf(s_track_buf, sizeof(s_track_buf), "%s", s_title);
  } else if (s_artist[0]) {
    snprintf(s_track_buf, sizeof(s_track_buf), "%s", s_artist);
  } else {
    s_track_buf[0] = '\0';
  }
  overlay_mark_dirty();
}

static void copy_cstr(char *dst, size_t dst_sz, Tuple *t) {
  if (!t || !dst || dst_sz == 0) {
    return;
  }
  if (t->type != TUPLE_CSTRING) {
    return;
  }
  strncpy(dst, t->value->cstring, dst_sz - 1);
  dst[dst_sz - 1] = '\0';
}

static void handle_track_info(DictionaryIterator *iter) {
  Tuple *title_t = dict_find(iter, MESSAGE_KEY_TrackTitle);
  Tuple *artist_t = dict_find(iter, MESSAGE_KEY_TrackArtist);
  Tuple *year_t = dict_find(iter, MESSAGE_KEY_TrackYear);
  if (!title_t && !artist_t && !year_t) {
    return;
  }
  copy_cstr(s_title, sizeof(s_title), title_t);
  copy_cstr(s_artist, sizeof(s_artist), artist_t);
  if (year_t) {
    s_year = year_t->value->int32;
  }
  update_track_line();
}

static void handle_playback_msg(DictionaryIterator *iter) {
  if (dict_find(iter, MESSAGE_KEY_ImageChunkIndex) || dict_find(iter, MESSAGE_KEY_ImageDataSize)) {
    return;
  }
  Tuple *pos_t = dict_find(iter, MESSAGE_KEY_TrackPositionMs);
  Tuple *dur_t = dict_find(iter, MESSAGE_KEY_TrackDurationMs);
  Tuple *play_t = dict_find(iter, MESSAGE_KEY_TrackPlaying);
  if (!pos_t && !dur_t && !play_t) {
    return;
  }
  int32_t pos = pos_t ? (int32_t)pos_t->value->int32 : s_position_ms;
  int32_t dur = dur_t ? (int32_t)dur_t->value->int32 : s_duration_ms;
  bool playing = play_t ? (play_t->value->int32 != 0) : s_playing;
  apply_playback(pos, dur, playing);
}

static void overlay_mark_dirty(void) {
  if (s_overlay_layer) {
    layer_mark_dirty(s_overlay_layer);
  }
}

static void draw_shadowed_text(GContext *ctx, const char *text, GFont font, GRect box,
                               GTextAlignment align) {
  if (!text || !text[0]) {
    return;
  }
  static const int8_t ox[] = {1, -1, 1, -1, 0, 2};
  static const int8_t oy[] = {1, 1, -1, -1, 1, 2};
  graphics_context_set_text_color(ctx, GColorBlack);
  for (int i = 0; i < 6; i++) {
    graphics_draw_text(ctx, text, font,
                       GRect(box.origin.x + ox[i], box.origin.y + oy[i], box.size.w, box.size.h),
                       GTextOverflowModeTrailingEllipsis, align, NULL);
  }
  graphics_context_set_text_color(ctx, GColorWhite);
  graphics_draw_text(ctx, text, font, box, GTextOverflowModeTrailingEllipsis, align, NULL);
}

static void overlay_update_proc(Layer *layer, GContext *ctx) {
  GRect b = layer_get_bounds(layer);
  GFont time_font = fonts_get_system_font(FONT_KEY_GOTHIC_24_BOLD);
  GFont small_font = fonts_get_system_font(FONT_KEY_GOTHIC_14);
  const int art_h = b.size.h - TRACK_TEXT_H;
  const int prog_y = art_h - PROGRESS_BAR_H;

  draw_shadowed_text(ctx, s_date_buf, small_font, GRect(3, 4, 60, 22), GTextAlignmentLeft);
  draw_shadowed_text(ctx, s_time_buf, time_font, GRect(48, -2, 104, 30), GTextAlignmentCenter);
  draw_shadowed_text(ctx, s_battery_buf, small_font, GRect(b.size.w - 63, 4, 60, 22),
                     GTextAlignmentRight);

  graphics_context_set_fill_color(ctx, GColorBlack);
  graphics_fill_rect(ctx, GRect(0, art_h, b.size.w, TRACK_TEXT_H), 0, GCornerNone);

  graphics_context_set_fill_color(ctx, GColorDarkGray);
  graphics_fill_rect(ctx, GRect(0, prog_y, b.size.w, PROGRESS_BAR_H), 0, GCornerNone);
  if (s_duration_ms > 0) {
    int fill_w = (int)((int64_t)s_position_ms * b.size.w / s_duration_ms);
    if (fill_w < 0) {
      fill_w = 0;
    }
    if (fill_w > b.size.w) {
      fill_w = b.size.w;
    }
    graphics_context_set_fill_color(ctx, s_playing ? GColorWhite : GColorLightGray);
    graphics_fill_rect(ctx, GRect(0, prog_y, fill_w, PROGRESS_BAR_H), 0, GCornerNone);
  }

  draw_shadowed_text(ctx, s_track_buf, small_font,
                     GRect(4, art_h + 2, b.size.w - 8, 18), GTextAlignmentCenter);
}

static void update_status_hint(void) {
  if (!s_status_layer) {
    return;
  }
  if (s_has_live_art) {
    text_layer_set_text(s_status_layer, "");
    layer_set_hidden(text_layer_get_layer(s_status_layer), true);
    return;
  }
  layer_set_hidden(text_layer_get_layer(s_status_layer), false);
  if (!s_connected) {
    text_layer_set_text(s_status_layer, "Open Pebble app on phone");
  } else {
    text_layer_set_text(s_status_layer,
                        "Play music on phone\n"
                        "(companion v1.2.4 +\n"
                        "notification access)");
  }
}

static void inbox_received(DictionaryIterator *iter, void *context) {
  if (dict_find(iter, MESSAGE_KEY_ImageDataSize) && dict_find(iter, MESSAGE_KEY_ImageChunksTotal)) {
    handle_image_header(iter);
    return;
  }
  if (dict_find(iter, MESSAGE_KEY_ImageChunkIndex)) {
    handle_image_chunk(iter);
    return;
  }
  handle_track_info(iter);
  handle_playback_msg(iter);
}

static void inbox_dropped(AppMessageResult reason, void *context) {
  APP_LOG(APP_LOG_LEVEL_WARNING, "inbox dropped %d", reason);
}

static void tick_handler(struct tm *tick_time, TimeUnits units_changed) {
  strftime(s_time_buf, sizeof(s_time_buf),
           clock_is_24h_style() ? "%H:%M" : "%I:%M", tick_time);
  strftime(s_date_buf, sizeof(s_date_buf), "%b %d", tick_time);
  overlay_mark_dirty();
  if ((units_changed & MINUTE_UNIT) && s_connected && !s_has_live_art) {
    request_refresh();
  }
}

static void battery_handler(BatteryChargeState state) {
  snprintf(s_battery_buf, sizeof(s_battery_buf), "%d%%", state.charge_percent);
  overlay_mark_dirty();
}

static void bt_handler(bool connected) {
  s_connected = connected;
  if (connected) {
    request_refresh();
  }
  update_status_hint();
}

static void window_load(Window *window) {
  Layer *root = window_get_root_layer(window);
  GRect bounds = layer_get_bounds(root);

  const int art_h = bounds.size.h - TRACK_TEXT_H;

  s_placeholder_bitmap = gbitmap_create_with_resource(RESOURCE_ID_PLACEHOLDER);
  s_bitmap_layer = bitmap_layer_create(GRect(0, 0, bounds.size.w, art_h));
  bitmap_layer_set_compositing_mode(s_bitmap_layer, GCompOpAssign);
  bitmap_layer_set_alignment(s_bitmap_layer, GAlignCenter);
  bitmap_layer_set_background_color(s_bitmap_layer, GColorBlack);
  if (s_placeholder_bitmap) {
    bitmap_layer_set_bitmap(s_bitmap_layer, s_placeholder_bitmap);
  }
  layer_add_child(root, bitmap_layer_get_layer(s_bitmap_layer));

  s_overlay_layer = layer_create(bounds);
  layer_set_update_proc(s_overlay_layer, overlay_update_proc);
  layer_add_child(root, s_overlay_layer);

  s_status_layer = text_layer_create(GRect(6, 80, bounds.size.w - 12, 72));
  text_layer_set_background_color(s_status_layer, GColorClear);
  text_layer_set_text_color(s_status_layer, GColorMelon);
  text_layer_set_font(s_status_layer, fonts_get_system_font(FONT_KEY_GOTHIC_14));
  text_layer_set_text_alignment(s_status_layer, GTextAlignmentCenter);
  text_layer_set_overflow_mode(s_status_layer, GTextOverflowModeWordWrap);
  layer_add_child(root, text_layer_get_layer(s_status_layer));
  update_status_hint();

  time_t now = time(NULL);
  tick_handler(localtime(&now), MINUTE_UNIT | HOUR_UNIT | DAY_UNIT);
  battery_handler(battery_state_service_peek());
}

static void window_unload(Window *window) {
  if (s_progress_timer) {
    app_timer_cancel(s_progress_timer);
    s_progress_timer = NULL;
  }
  bitmap_layer_destroy(s_bitmap_layer);
  layer_destroy(s_overlay_layer);
  text_layer_destroy(s_status_layer);
  if (s_art_bitmap) {
    gbitmap_destroy(s_art_bitmap);
    s_art_bitmap = NULL;
  }
  if (s_placeholder_bitmap) {
    gbitmap_destroy(s_placeholder_bitmap);
    s_placeholder_bitmap = NULL;
  }
  free_png();
}

static void init(void) {
  s_window = window_create();
  window_set_background_color(s_window, GColorBlack);
  window_set_window_handlers(s_window, (WindowHandlers){
      .load = window_load,
      .unload = window_unload,
  });
  window_stack_push(s_window, true);

  app_message_register_inbox_received(inbox_received);
  app_message_register_inbox_dropped(inbox_dropped);
  app_message_open(INBOX_SIZE, OUTBOX_SIZE);

  tick_timer_service_subscribe(MINUTE_UNIT, tick_handler);
  battery_state_service_subscribe(battery_handler);
  s_connected = connection_service_peek_pebble_app_connection() ||
                connection_service_peek_pebblekit_connection();
  connection_service_subscribe((ConnectionHandlers){
      .pebble_app_connection_handler = bt_handler,
      .pebblekit_connection_handler = bt_handler,
  });
  update_status_hint();
  if (s_connected) {
    request_refresh();
  }
}

static void deinit(void) {
  battery_state_service_unsubscribe();
  window_destroy(s_window);
}

int main(void) {
  init();
  app_event_loop();
  deinit();
}

using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;

namespace Manager
{
    public class ScheduledTask
    {
        public string Id { get; set; } = Guid.NewGuid().ToString("N").Substring(0, 6);
        public DateTime RunAt { get; set; }
        public string Command { get; set; } = "";
        public bool IsDaily { get; set; } = false;
        public string DailyTime { get; set; } = "";
        public bool IsExecuted { get; set; } = false;
        public DateTime CreatedAt { get; set; } = DateTime.Now;
    }

    public class TaskScheduler : IDisposable
    {
        private readonly List<ScheduledTask> _tasks = new List<ScheduledTask>();
        private readonly object _lock = new object();
        private readonly string _filePath;
        private CancellationTokenSource _cts;
        private Func<string, Task<string>> _executor;
        private Action<string> _log;
        private Action<string> _notifyTele;

        public TaskScheduler(string filePath = null)
        {
            _filePath = filePath ?? Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "schedules.json");
            Load();
        }

        public void Load()
        {
            lock (_lock)
            {
                _tasks.Clear();
                if (!File.Exists(_filePath)) return;
                try
                {
                    string json = File.ReadAllText(_filePath, Encoding.UTF8);
                    var list = JsonSerializer.Deserialize<List<ScheduledTask>>(json);
                    if (list != null)
                    {
                        var now = DateTime.Now;
                        foreach (var t in list)
                        {
                            // Nếu là lịch daily mà thời gian đã qua, tiến tới ngày tiếp theo
                            if (t.IsDaily)
                            {
                                while (t.RunAt <= now) t.RunAt = t.RunAt.AddDays(1);
                                t.IsExecuted = false;
                            }
                            _tasks.Add(t);
                        }
                    }
                }
                catch (Exception ex)
                {
                    _log?.Invoke($"⚠️ Không đọc được schedules.json: {ex.Message}");
                }
            }
        }

        public void Save()
        {
            lock (_lock)
            {
                try
                {
                    var opt = new JsonSerializerOptions { WriteIndented = true };
                    string json = JsonSerializer.Serialize(_tasks, opt);
                    File.WriteAllText(_filePath, json, Encoding.UTF8);
                }
                catch (Exception ex)
                {
                    _log?.Invoke($"⚠️ Không lưu được schedules.json: {ex.Message}");
                }
            }
        }

        public ScheduledTask ThemLich(DateTime runAt, string command, bool isDaily = false, string dailyTime = "")
        {
            var task = new ScheduledTask
            {
                RunAt = runAt,
                Command = command.Trim(),
                IsDaily = isDaily,
                DailyTime = dailyTime,
                IsExecuted = false,
                CreatedAt = DateTime.Now
            };

            lock (_lock)
            {
                _tasks.Add(task);
                Save();
            }
            return task;
        }

        public bool HuyLich(string id)
        {
            lock (_lock)
            {
                int removed = _tasks.RemoveAll(t => string.Equals(t.Id, id, StringComparison.OrdinalIgnoreCase));
                if (removed > 0)
                {
                    Save();
                    return true;
                }
            }
            return false;
        }

        public int HuyTatCa()
        {
            lock (_lock)
            {
                int count = _tasks.Count;
                _tasks.Clear();
                Save();
                return count;
            }
        }

        public List<ScheduledTask> LayDanhSach()
        {
            lock (_lock)
            {
                // Trả về các task chưa chạy hoặc task daily
                return _tasks.Where(t => !t.IsExecuted || t.IsDaily)
                             .OrderBy(t => t.RunAt)
                             .ToList();
            }
        }

        public void Start(Func<string, Task<string>> executor, Action<string> log, Action<string> notifyTele)
        {
            _executor = executor;
            _log = log;
            _notifyTele = notifyTele;
            _cts = new CancellationTokenSource();

            Task.Run(async () =>
            {
                var ct = _cts.Token;
                while (!ct.IsCancellationRequested)
                {
                    try
                    {
                        await Task.Delay(1000, ct);
                        KiemTraVaChayTasks();
                    }
                    catch (OperationCanceledException) { break; }
                    catch (Exception ex)
                    {
                        _log?.Invoke($"⚠️ Scheduler lỗi vòng lặp: {ex.Message}");
                    }
                }
            });
        }

        private void KiemTraVaChayTasks()
        {
            var now = DateTime.Now;
            List<ScheduledTask> dueTasks = new List<ScheduledTask>();

            lock (_lock)
            {
                foreach (var t in _tasks)
                {
                    if (!t.IsExecuted && t.RunAt <= now)
                    {
                        dueTasks.Add(t);
                        t.IsExecuted = true;
                    }
                }
            }

            if (dueTasks.Count == 0) return;

            foreach (var task in dueTasks)
            {
                _ = Task.Run(async () =>
                {
                    try
                    {
                        string logMsg = $"⏰ [Hẹn giờ #{task.Id}] Kích hoạt lệnh: {task.Command}";
                        _log?.Invoke(logMsg);
                        _notifyTele?.Invoke($"⏰ <b>[Hẹn giờ #{task.Id}]</b> Kích hoạt lệnh: <code>{TelegramBot.Esc(task.Command)}</code>");

                        string ketQua = "";
                        if (_executor != null)
                        {
                            ketQua = await _executor(task.Command);
                        }

                        if (!string.IsNullOrWhiteSpace(ketQua))
                        {
                            _notifyTele?.Invoke($"⏰ <b>[Hẹn giờ #{task.Id}] Kết quả:</b>\n{ketQua}");
                        }
                    }
                    catch (Exception ex)
                    {
                        _log?.Invoke($"❌ [Hẹn giờ #{task.Id}] Thực thi thất bại: {ex.Message}");
                        _notifyTele?.Invoke($"❌ <b>[Hẹn giờ #{task.Id}] Lỗi:</b> {TelegramBot.Esc(ex.Message)}");
                    }
                    finally
                    {
                        lock (_lock)
                        {
                            if (task.IsDaily)
                            {
                                task.RunAt = task.RunAt.AddDays(1);
                                while (task.RunAt <= DateTime.Now) task.RunAt = task.RunAt.AddDays(1);
                                task.IsExecuted = false;
                            }
                            else
                            {
                                // Xoá task một lần sau khi chạy xong để giữ danh sách gọn gàng
                                _tasks.Remove(task);
                            }
                            Save();
                        }
                    }
                });
            }
        }

        public void Dispose()
        {
            try { _cts?.Cancel(); } catch { }
            try { _cts?.Dispose(); } catch { }
        }
    }
}

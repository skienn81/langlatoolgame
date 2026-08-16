using System;
using System.Collections.Generic;
using System.IO;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace Manager
{
    public class QuizManager
    {
        private static readonly object _lock = new object();
        private static QuizManager _instance;
        public static QuizManager Instance => _instance ??= new QuizManager();

        private string _dbPath;
        private Dictionary<string, string> _quizDb = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);

        public QuizManager()
        {
            _dbPath = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "quiz_database.json");
            LoadDatabase();
        }

        public string NormalizeText(string text)
        {
            if (string.IsNullOrWhiteSpace(text)) return "";
            string s = text.Trim().ToLowerInvariant();
            s = RemoveVietnameseAccents(s);
            s = Regex.Replace(s, @"[^\w\s]", " ");
            s = Regex.Replace(s, @"\s+", " ").Trim();
            return s;
        }

        private string RemoveVietnameseAccents(string text)
        {
            string[] arr1 = new string[] { "á", "à", "ả", "ã", "ạ", "â", "ấ", "ầ", "ẩ", "ẫ", "ậ", "ă", "ắ", "ằ", "ẳ", "ẵ", "ặ",
                "đ",
                "é","è","ẻ","ẽ","ẹ","ê","ế","ề","ể","ễ","ệ",
                "í","ì","ỉ","ĩ","ị",
                "ó","ò","ỏ","õ","ọ","ô","ố","ồ","ổ","ỗ","ộ","ơ","ớ","ờ","ở","ỡ","ợ",
                "ú","ù","ủ","ũ","ụ","ư","ứ","ừ","ử","ữ","ự",
                "ý","ỳ","ỷ","ỹ","ỵ"};
            string[] arr2 = new string[] { "a", "a", "a", "a", "a", "a", "a", "a", "a", "a", "a", "a", "a", "a", "a", "a", "a",
                "d",
                "e","e","e","e","e","e","e","e","e","e","e",
                "i","i","i","i","i",
                "o","o","o","o","o","o","o","o","o","o","o","o","o","o","o","o","o",
                "u","u","u","u","u","u","u","u","u","u","u",
                "y","y","y","y","y"};
            for (int i = 0; i < arr1.Length; i++)
            {
                text = text.Replace(arr1[i], arr2[i]);
                text = text.Replace(arr1[i].ToUpper(), arr2[i]);
            }
            return text;
        }

        public void LoadDatabase()
        {
            lock (_lock)
            {
                try
                {
                    if (File.Exists(_dbPath))
                    {
                        string json = File.ReadAllText(_dbPath, Encoding.UTF8);
                        var data = JsonSerializer.Deserialize<Dictionary<string, string>>(json);
                        if (data != null)
                        {
                            _quizDb = new Dictionary<string, string>(data, StringComparer.OrdinalIgnoreCase);
                        }
                    }
                }
                catch (Exception ex)
                {
                    Console.WriteLine("Error loading quiz db: " + ex.Message);
                }
            }
        }

        public void SaveDatabase()
        {
            lock (_lock)
            {
                try
                {
                    string json = JsonSerializer.Serialize(_quizDb, new JsonSerializerOptions { WriteIndented = true });
                    File.WriteAllText(_dbPath, json, Encoding.UTF8);
                }
                catch (Exception ex)
                {
                    Console.WriteLine("Error saving quiz db: " + ex.Message);
                }
            }
        }

        public string GetCorrectAnswer(string rawQuestion)
        {
            string norm = NormalizeText(rawQuestion);
            if (string.IsNullOrEmpty(norm)) return null;

            lock (_lock)
            {
                if (_quizDb.TryGetValue(norm, out string ans))
                {
                    return ans;
                }
            }
            return null;
        }

        public bool SaveCorrectAnswer(string rawQuestion, string rawAnswer)
        {
            string norm = NormalizeText(rawQuestion);
            if (string.IsNullOrEmpty(norm) || string.IsNullOrWhiteSpace(rawAnswer)) return false;

            lock (_lock)
            {
                _quizDb[norm] = rawAnswer.Trim();
                SaveDatabase();
            }
            return true;
        }

        public Dictionary<string, string> GetAll()
        {
            lock (_lock)
            {
                return new Dictionary<string, string>(_quizDb);
            }
        }
    }
}

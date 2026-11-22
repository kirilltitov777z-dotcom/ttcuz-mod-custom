import unittest

from ttcuz.dexpatch import text_limit_rules

SCENE = "Lcom/ss/android/ugc/aweme/shortvideo/mvtemplate/choosemedia/MvChoosePhotoScene;"
INPUT = "Lcom/ss/android/ugc/aweme/comment/model/CommentInputContent;"


class CommentPhotosRule(unittest.TestCase):
    def test_limit_and_list_hooked_once(self):
        text = (".class public final " + SCENE + "\n.method public final a(I)V\n    .registers 3\n"
                "    iput p1, p0, " + SCENE + "->maxPhotoSelectCount:I\n"
                "    iput-object v1, v0, " + INPUT + "->imageList:Ljava/util/List;\n    return-void\n.end method\n")
        patched, counts = text_limit_rules(text)
        self.assertEqual(counts["comment photo limit"], 1)
        self.assertEqual(counts["comment photo list"], 1)
        self.assertIn("invoke-static {p0, p1}, Lcat/ttcuz/framework/CommentPhotos;->limit(Ljava/lang/Object;I)I\n    move-result p1\n    iput p1", patched)
        self.assertIn("->images(Ljava/util/List;)Ljava/util/List;\n    move-result-object v1\n    iput-object v1", patched)
        self.assertEqual(text_limit_rules(patched)[0], patched)


if __name__ == "__main__":
    unittest.main()

prop() {
  grep "^[[:space:]]*${1}" gradle.properties | cut -d'=' -f2 | sed 's/^[[:space:]]*//; s/\r//'
}

project_id="lophine"
project_id_b="Lophine"

commitid=$(git log --pretty='%h' -1)
mcversion=$(prop mcVersion)
release=$(prop release)
pushRepo=$(prop pushRepo)
release_tag="$mcversion-$commitid"
jarName="$project_id-$mcversion-paperclip.jar"
jarName_dir="lophine-server/build/libs/$jarName"

flag_push_repo=false
flag_release=false
pre=false

if [ "$release" = "pre" ]; then
  pre=true
  flag_release=true
  make_latest=true
  flag_push_repo=true
elif [ "$release" = "true" ]; then
  flag_release=true
  make_latest=true
  flag_push_repo=true
fi

if [ "$pushRepo" = "true" ]; then
  flag_push_repo=true
elif [ "$pushRepo" = "false" ]; then
  flag_push_repo=false
fi

actual_jar=$(ls lophine-server/build/libs/$project_id-paperclip-*.jar 2>/dev/null | head -n 1)
if [ -n "$actual_jar" ]; then
  mv "$actual_jar" "$jarName_dir"
else
  echo "Warning: No paperclip jar found to rename"
fi

echo "project_id=$project_id" >> $GITHUB_ENV
echo "project_id_b=$project_id_b" >> $GITHUB_ENV
echo "commit_id=$commitid" >> $GITHUB_ENV
echo "commit_msg=$(git log --pretty='> [%h] %s' -1)" >> $GITHUB_ENV
echo "mcversion=$mcversion" >> $GITHUB_ENV
echo "pre=$pre" >> $GITHUB_ENV
echo "tag=$release_tag" >> $GITHUB_ENV
echo "jar=$jarName" >> $GITHUB_ENV
echo "jar_dir=$jarName_dir" >> $GITHUB_ENV
echo "flag_push_repo=$flag_push_repo" >> $GITHUB_ENV
echo "flag_release=$flag_release" >> $GITHUB_ENV
echo "make_latest=$make_latest" >> $GITHUB_ENV
